#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-project"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:3306/cowork_project?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul}"
   export SPRING_KAFKA_BOOTSTRAP_SERVERS="${SPRING_KAFKA_BOOTSTRAP_SERVERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="${EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:-http://localhost:8761/eureka/}"
   export GITHUB_APP_SERVICE_URL="${GITHUB_APP_SERVICE_URL:-http://localhost:3000}"
   exec ./gradlew :cowork-project:run'
)

run_managed_service "${1:-start}"
