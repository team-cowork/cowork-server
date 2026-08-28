#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-notification"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-notification"
SERVICE_COMMAND=(
  bash -lc
  'export APP_CONFIG_URL="http://localhost:8761"
   export APP_PROFILE="local"
   export DB_DSN="${DB_DSN:-${MYSQL_USER}:${MYSQL_PASSWORD}@tcp(localhost:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local}"
   export KAFKA_BROKERS="${KAFKA_BROKERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export KAFKA_TOPIC_NOTIFICATION="${KAFKA_TOPIC_NOTIFICATION:-notification.trigger}"
   export KAFKA_GROUP_ID="${KAFKA_GROUP_ID:-cowork-notification}"
   export KAFKA_PROJECTION_GROUP_ID="${KAFKA_PROJECTION_GROUP_ID:-cowork-notification-projections}"
   export KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE="${KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE:-preference.channel-notification.changed}"
   export KAFKA_TOPIC_USER_PROFILE="${KAFKA_TOPIC_USER_PROFILE:-user.profile.event}"
   export KAFKA_TOPIC_TEAM_LIFECYCLE="${KAFKA_TOPIC_TEAM_LIFECYCLE:-team.lifecycle}"
   export FCM_CREDENTIALS_FILE="${FCM_CREDENTIALS_FILE:-../docker/secrets/firebase-credentials.json}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export EUREKA_INSTANCE_HOST="${EUREKA_INSTANCE_HOST:-localhost}"
   exec go run ./cmd/server/'
)

run_managed_service "${1:-start}"
