#!/bin/bash
# Docker-published ports bypass ufw INPUT rules; filter their original destination.
prepare_config_firewall() {
  export BIND_IP HOST_PORT CONFIG_ALLOWED_CIDRS
  python3 "${PROD_DIR}/config/config-access.py" firewall >/dev/null || return 1
  (umask 077 && python3 "${PROD_DIR}/network/config-firewall.py" render > "${DEPLOY_SETTINGS_DIR}/config-firewall.json") || return 1
  CONFIG_FIREWALL_TRANSACTION=$(python3 -c 'import uuid; print(uuid.uuid4())')
}

# Called only after the image and candidate container have been prepared.
before_container_cutover() {
  # An operator installs this fixed root-owned helper before the first deploy.
  # Release-cache code only renders data; it is never executed through sudo.
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py prepare "$CONFIG_FIREWALL_TRANSACTION" \
    < "${DEPLOY_SETTINGS_DIR}/config-firewall.json" || return 1
  CONFIG_FIREWALL_CHANGED=true
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py activate "$CONFIG_FIREWALL_TRANSACTION"
}

rollback_container_cutover() {
  [ "${CONFIG_FIREWALL_CHANGED:-false}" = true ] || return 0
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py rollback "$CONFIG_FIREWALL_TRANSACTION" || return 1
  echo '[deploy] Config firewall runtime and boot policy restored; initial protection is retained' >&2
}

finalize_container_cutover() {
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py finalize "$CONFIG_FIREWALL_TRANSACTION"
}
