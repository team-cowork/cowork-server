#!/bin/bash
# Existing initialized volumes and root-installed ingress policy are prerequisites.
require_env DATASTORE_SETTINGS_JSON
python3 "${PROD_DIR}/datastores/runtime.py" "$ACTION"
