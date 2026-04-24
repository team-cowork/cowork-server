#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-notification"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-notification"
SERVICE_COMMAND=(env APP_CONFIG_URL=http://localhost:8761 APP_PROFILE=local go run ./cmd/server/)

run_managed_service "${1:-start}"
