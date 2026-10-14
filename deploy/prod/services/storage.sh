#!/bin/bash
# Host bootstrap is separate. Normal CD only renders private data and uses Docker.
require_env STORAGE_SETTINGS_JSON
python3 "${PROD_DIR}/storage/runtime.py" "$ACTION"
