#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-preference"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=("$PROJECT_ROOT/gradlew" ":cowork-preference:run")

run_managed_service "${1:-start}"
