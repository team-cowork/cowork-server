#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-roadmap"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export SPRING_R2DBC_URL="${SPRING_R2DBC_URL:-r2dbc:mysql://localhost:3306/cowork_roadmap?serverZoneId=Asia/Seoul}"
   export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:3306/cowork_roadmap?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul}"
   export SPRING_FLYWAY_URL="${SPRING_FLYWAY_URL:-jdbc:mysql://localhost:3306/cowork_roadmap?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul}"
   export SPRING_KAFKA_BOOTSTRAP_SERVERS="${SPRING_KAFKA_BOOTSTRAP_SERVERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="${EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:-http://localhost:8761/eureka/}"
   exec ./gradlew :cowork-roadmap:bootRun'
)

run_managed_service "${1:-start}"
