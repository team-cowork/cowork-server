#!/bin/bash
# Shared engines use existing volumes and root-installed ingress policy.
require_env DATASTORE_SETTINGS_JSON
python3 "${PROD_DIR}/datastores/runtime.py" "$ACTION"
