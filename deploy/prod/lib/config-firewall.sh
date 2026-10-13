#!/bin/bash
# Docker-published ports bypass ufw INPUT rules; filter their original destination.
prepare_config_firewall() {
  export BIND_IP HOST_PORT CONFIG_ALLOWED_CIDRS
  python3 "${PROD_DIR}/config-access.py" firewall >/dev/null || return 1
  (umask 077 && python3 "${PROD_DIR}/config-firewall.py" render > "${DEPLOY_SETTINGS_DIR}/config-firewall.json") || return 1
  CONFIG_FIREWALL_TRANSACTION=$(python3 -c 'import uuid; print(uuid.uuid4())')
}

# Called only after the image and candidate container have been prepared.
before_container_cutover() {
  # Install a root-owned copy outside the release directory, and preserve the
  # previous policy before changing either the live or the boot-time policy.
  sudo -n /usr/bin/python3 -I "${PROD_DIR}/config-firewall.py" prepare "$CONFIG_FIREWALL_TRANSACTION" \
    < "${DEPLOY_SETTINGS_DIR}/config-firewall.json" || return 1
  CONFIG_FIREWALL_CHANGED=true
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py activate "$CONFIG_FIREWALL_TRANSACTION" || return 1
  # Starting an already active oneshot is harmless. Do not restart this required
  # unit: stopping it explicitly would also stop Docker.
  sudo -n systemctl start cowork-config-firewall.service
}

rollback_container_cutover() {
  [ "${CONFIG_FIREWALL_CHANGED:-false}" = true ] || return 0
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-config-firewall.py rollback "$CONFIG_FIREWALL_TRANSACTION" || return 1
  sudo -n systemctl start cowork-config-firewall.service || return 1
  echo '[deploy] Config firewall runtime and boot policy restored; initial protection is retained' >&2
}
