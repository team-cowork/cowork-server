#!/bin/bash
# Docker-published ports bypass ufw INPUT rules; filter their original destination.
prepare_config_firewall() {
  local rules="${DEPLOY_SETTINGS_DIR}/config-firewall.rules"
  export BIND_IP HOST_PORT CONFIG_ALLOWED_CIDRS
  (umask 077 && python3 "${PROD_DIR}/config-access.py" firewall > "$rules")
}

config_firewall_jump_exists() {
  sudo -n iptables -w -C DOCKER-USER -p tcp -m conntrack --ctdir ORIGINAL \
    --ctorigdst "$BIND_IP" --ctorigdstport "$HOST_PORT" -j COWORK_CONFIG 2>/dev/null
}

apply_config_firewall_rules() {
  sudo -n iptables-restore --wait --noflush < "${DEPLOY_SETTINGS_DIR}/config-firewall.rules" || return 1
  if ! config_firewall_jump_exists; then
    sudo -n iptables -w -I DOCKER-USER 1 -p tcp -m conntrack --ctdir ORIGINAL \
      --ctorigdst "$BIND_IP" --ctorigdstport "$HOST_PORT" -j COWORK_CONFIG || return 1
  fi
}

# Called only after the image and candidate container have been prepared.
before_container_cutover() {
  local snapshot="${DEPLOY_SETTINGS_DIR}/config-firewall.before"
  local restore="${DEPLOY_SETTINGS_DIR}/config-firewall.restore"
  sudo -n iptables -w -n -L DOCKER-USER >/dev/null || return 1
  (umask 077 && sudo -n iptables -w -S > "$snapshot") || return 1
  CONFIG_FIREWALL_HAD_POLICY=false
  CONFIG_FIREWALL_HAD_JUMP=false
  if awk '$1 == "-N" && $2 == "COWORK_CONFIG" { found = 1 } END { exit !found }' "$snapshot" &&
    awk '$1 == "-A" && $2 == "DOCKER-USER" && / -j COWORK_CONFIG$/ { found = 1 } END { exit !found }' "$snapshot"; then
    CONFIG_FIREWALL_HAD_POLICY=true
    # Restore only our chain. Docker and other services may change their own rules.
    (umask 077
      {
        printf '*filter\n:COWORK_CONFIG - [0:0]\n-F COWORK_CONFIG\n'
        awk '$1 == "-A" && $2 == "COWORK_CONFIG"' "$snapshot"
        printf 'COMMIT\n'
      } > "$restore") || return 1
  fi
  if config_firewall_jump_exists; then CONFIG_FIREWALL_HAD_JUMP=true; fi
  # Mark before either mutation so a partial application is also recovered.
  CONFIG_FIREWALL_CHANGED=true
  apply_config_firewall_rules
}

rollback_container_cutover() {
  [ "${CONFIG_FIREWALL_CHANGED:-false}" = true ] || return 0
  if [ "$CONFIG_FIREWALL_HAD_POLICY" = true ]; then
    sudo -n iptables-restore --wait --noflush < "${DEPLOY_SETTINGS_DIR}/config-firewall.restore" || return 1
    if [ "$CONFIG_FIREWALL_HAD_JUMP" = false ] && config_firewall_jump_exists; then
      sudo -n iptables -w -D DOCKER-USER -p tcp -m conntrack --ctdir ORIGINAL \
        --ctorigdst "$BIND_IP" --ctorigdstport "$HOST_PORT" -j COWORK_CONFIG || return 1
    fi
    echo '[deploy] Previous Config firewall policy restored' >&2
  else
    # The previous unauthenticated server must not return to unrestricted ingress.
    apply_config_firewall_rules || return 1
    echo '[deploy] Initial private Config firewall policy retained during rollback' >&2
  fi
}
