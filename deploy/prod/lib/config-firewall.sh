#!/bin/bash
# Docker-published ports bypass ufw INPUT rules; filter their original destination.
configure_config_firewall() {
  local rules="${DEPLOY_SETTINGS_DIR}/config-firewall.rules"
  export BIND_IP HOST_PORT CONFIG_ALLOWED_CIDRS
  python3 "${PROD_DIR}/config-access.py" firewall > "$rules"
  [ "$ACTION" = deploy ] || return 0
  # Fail before cutover if the host uses an unsupported firewall backend or lacks privileges.
  sudo -n iptables -w -n -L DOCKER-USER >/dev/null
  sudo -n iptables-restore --wait --noflush < "$rules"
  if ! sudo -n iptables -w -C DOCKER-USER -p tcp -m conntrack --ctdir ORIGINAL \
    --ctorigdst "$BIND_IP" --ctorigdstport "$HOST_PORT" -j COWORK_CONFIG 2>/dev/null; then
    sudo -n iptables -w -I DOCKER-USER 1 -p tcp -m conntrack --ctdir ORIGINAL \
      --ctorigdst "$BIND_IP" --ctorigdstport "$HOST_PORT" -j COWORK_CONFIG
  fi
}
