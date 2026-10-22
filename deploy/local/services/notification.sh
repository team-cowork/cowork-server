#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-notification"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-notification"
SERVICE_COMMAND=(
  bash -lc
  'export DB_PATH="${DB_PATH:-$PWD/data/notification.db}"
   mkdir -p "$(dirname "$DB_PATH")"
   export CONFIG_SERVER_URL="http://localhost:8761"
   export APP_CONFIG_PROFILE="local"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export KAFKA_TOPIC_NOTIFICATION="${KAFKA_TOPIC_NOTIFICATION:-notification.trigger}"
   export KAFKA_GROUP_ID="${KAFKA_GROUP_ID:-cowork-notification}"
   export KAFKA_PROJECTION_GROUP_ID="${KAFKA_PROJECTION_GROUP_ID:-cowork-notification-projections}"
   export KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE="${KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE:-preference.channel-notification.changed}"
   export KAFKA_TOPIC_USER_PROFILE="${KAFKA_TOPIC_USER_PROFILE:-user.profile.event.v2}"
   export KAFKA_TOPIC_TEAM_LIFECYCLE="${KAFKA_TOPIC_TEAM_LIFECYCLE:-team.lifecycle}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export APP_ADVERTISE_ADDRESS="${APP_ADVERTISE_ADDRESS:-localhost}"
   exec go run ./cmd/server/'
)

run_managed_service "${1:-start}"
