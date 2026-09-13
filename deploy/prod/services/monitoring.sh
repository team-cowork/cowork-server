#!/bin/bash
require_env CONFIG_SERVER_URL EUREKA_SERVER_URL MYSQL_HOST REDIS_HOST DEPLOY_SHA MONITORING_BIND_IP MONITORING_VOLUME_PREFIX GRAFANA_ADMIN_PASSWORD DISCORD_WEBHOOK_URL \
  MYSQL_EXPORTER_PASSWORD KAFKA_EXPORTER_SERVER POSTGRES_EXPORTER_DSN MONGO_EXPORTER_URI
# Generated runtime configuration stays outside the immutable source bundle.
export MONITORING_PROMETHEUS_CONFIG="${DEPLOY_SETTINGS_DIR}/prometheus.json"
if [ "$ACTION" = deploy ]; then
  mkdir -p "$(dirname "$MONITORING_PROMETHEUS_CONFIG")"
  python3 "${PROD_DIR}/monitoring/render-prometheus.py" "$MONITORING_PROMETHEUS_CONFIG"
fi
COMPOSE=(docker compose --project-directory "$RELEASE_ROOT" --env-file /dev/null
  -p "${MONITORING_COMPOSE_PROJECT:-cowork-monitoring}" -f "${PROD_DIR}/monitoring/compose.yaml")
"${COMPOSE[@]}" config --quiet
[ "$ACTION" = deploy ] || { echo '[monitoring] Configuration valid'; return; }
"${COMPOSE[@]}" pull
# up applies changed mounts/env/settings and waits for the init service; restart does not.
"${COMPOSE[@]}" up -d --force-recreate --wait --wait-timeout 180
health_host="${MONITORING_ADMIN_BIND_IP:-127.0.0.1}"
[ "$health_host" != 0.0.0.0 ] || health_host=127.0.0.1
wait_for_health "http://${health_host}:9090/-/ready" || fail 'Prometheus is not ready'
echo '[monitoring] Healthy'
