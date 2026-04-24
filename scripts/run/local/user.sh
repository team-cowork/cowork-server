#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-user"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:3306/cowork_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul}"
   exec ./gradlew :cowork-user:bootRun'
)

run_managed_service "${1:-start}"
