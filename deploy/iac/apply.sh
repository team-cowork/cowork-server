#!/usr/bin/env bash
set -euo pipefail
umask 077
IAC_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
case "${1:-}" in
  hosts)
    shift
    export ANSIBLE_CONFIG="$IAC_DIR/ansible/ansible.cfg"
    export ANSIBLE_HOST_KEY_CHECKING=True
    exec ansible-playbook -i "${IAC_INVENTORY:?Set an absolute private inventory path}" \
      "$IAC_DIR/ansible/site.yml" "$@"
    ;;
  *) echo 'Usage: deploy/iac/apply.sh hosts [ansible options]' >&2; exit 1 ;;
esac
