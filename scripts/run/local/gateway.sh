#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-gateway"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export SPRING_CONFIG_IMPORT="${SPRING_CONFIG_IMPORT:-configserver:http://localhost:8761}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="${EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:-http://localhost:8761/eureka/}"
   exec ./gradlew :cowork-gateway:bootRun'
)

run_managed_service "${1:-start}"
