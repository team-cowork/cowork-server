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
  grafana)
    action=${2:-plan}
    case "$action" in plan|apply) ;; *) echo 'Expected grafana plan or grafana apply' >&2; exit 1 ;; esac
    : "${TF_STATE_PATH:?State must be outside the checkout and the managed VMs}"
    : "${TF_DATA_DIR:?Set a private Terraform working directory outside the checkout}"
    : "${GRAFANA_URL:?Use an SSH tunnel to the loopback Grafana listener}"
    : "${GRAFANA_AUTH:?Use a Grafana service account token or bootstrap Basic Auth}"
    python3 - "$TF_STATE_PATH" "$TF_DATA_DIR" "$IAC_DIR" <<'PY'
import os, sys
from pathlib import Path
root = Path(sys.argv[3]).resolve().parents[1]
for raw in sys.argv[1:3]:
    path = Path(raw)
    if not path.is_absolute() or path.resolve().is_relative_to(root):
        raise SystemExit('Terraform state and working directory must be absolute paths outside the checkout')
    if path.exists() and (path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077):
        raise SystemExit('Terraform local storage must be private and owned by the invoking user')
PY
    mkdir -p "$TF_DATA_DIR" "$(dirname -- "$TF_STATE_PATH")"
    terraform -chdir="$IAC_DIR/terraform" init -input=false -reconfigure -lockfile=readonly \
      -backend-config="path=$TF_STATE_PATH"
    terraform -chdir="$IAC_DIR/terraform" validate
    plan_path="$TF_DATA_DIR/cowork.tfplan"
    trap 'rm -f -- "$plan_path"' EXIT
    terraform -chdir="$IAC_DIR/terraform" plan -input=false -out="$plan_path"
    if [ "$action" = apply ]; then
      terraform -chdir="$IAC_DIR/terraform" apply -input=false "$plan_path"
    fi
    ;;
  *) echo 'Usage: deploy/iac/apply.sh hosts [ansible options] | grafana [plan|apply]' >&2; exit 1 ;;
esac
