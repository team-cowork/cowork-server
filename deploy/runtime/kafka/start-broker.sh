#!/bin/bash
set -euo pipefail
: "${KAFKA_CLUSTER_ID:?KAFKA_CLUSTER_ID is required}"
if [[ ! "$KAFKA_CLUSTER_ID" =~ ^[A-Za-z0-9_-]{22}$ ]]; then
  echo 'KAFKA_CLUSTER_ID must be a Kafka UUID' >&2
  exit 1
fi
/opt/kafka/bin/kafka-storage.sh format \
  --cluster-id "$KAFKA_CLUSTER_ID" \
  --config /local/kafka/server.properties \
  --ignore-formatted
exec /opt/kafka/bin/kafka-server-start.sh /local/kafka/server.properties
