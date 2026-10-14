#!/bin/bash
# The administrator stages desired.json; CD can only transition that reviewed policy.
prepare_backend_firewall() {
  BACKEND_FIREWALL_TRANSACTION=$(python3 -c 'import uuid; print(uuid.uuid4())')
}

before_container_cutover() {
  python3 "${PROD_DIR}/slots.py" firewall "$DEPLOY_TARGET" |
    sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py prepare "$BACKEND_FIREWALL_TRANSACTION" || return 1
  BACKEND_FIREWALL_CHANGED=true
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py activate "$BACKEND_FIREWALL_TRANSACTION"
}

rollback_container_cutover() {
  [ "${BACKEND_FIREWALL_CHANGED:-false}" = true ] || return 0
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py rollback "$BACKEND_FIREWALL_TRANSACTION" || return 1
  echo '[deploy] Previous backend ingress and boot policy restored' >&2
}

finalize_container_cutover() {
  sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py finalize "$BACKEND_FIREWALL_TRANSACTION"
}
