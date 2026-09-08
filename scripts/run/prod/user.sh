#!/bin/bash
# cowork-user 배포 스크립트 (VM: 21133) — 다른 8개와 달리 Docker가 아니라 Elixir
# 네이티브 릴리즈로 돈다. CD의 build-images job이 cowork-user Docker 이미지도
# 만들지만, 이 서비스는 실제로 그 이미지를 쓰지 않는다(서버에서 소스로 직접
# mix release 빌드) — 이 스크립트는 그 기존 방식을 그대로 저장소로 옮긴 것이며,
# Docker 기반으로 바꾸는 건 이번 스코프가 아니다(검증 안 된 별도 변경이라 위험).
#
# git pull 대신 CD가 넘겨준 정확한 SHA로 checkout해서, 다른 서비스들과 동일하게
# "이 배포가 어떤 커밋을 반영했는지"가 결정적으로 고정되게 한다.
set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

REPO_ROOT="$HOME/cowork-server"
INFRA_HOST="10.0.0.93"
PORT=8082
SELF_IP="$(hostname -I | awk '{print $1}')"

# shellcheck source=/dev/null
source "$HOME/.asdf/asdf.sh"

cd "${REPO_ROOT}"
git fetch origin --quiet
git checkout "${DEPLOY_SHA}"

cd "${REPO_ROOT}/cowork-user"
export MIX_ENV=prod
mix deps.get
mix compile
mix release --overwrite

echo "[user] fetching SECRET_KEY_BASE from config server"
SECRET_KEY_BASE=$(curl -sf "http://${INFRA_HOST}:8761/cowork-user/local" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print([ps['source']['SECRET_KEY_BASE'] for ps in d['propertySources'] if 'SECRET_KEY_BASE' in ps.get('source', {})][0])")

OLD_PID=$(pgrep -f 'erts.*bin/beam.smp.*cowork_user' || true)
if [ -n "${OLD_PID}" ]; then
  echo "[user] stopping previous release (pid ${OLD_PID})"
  kill -TERM "${OLD_PID}"
  sleep 3
fi

cd "${REPO_ROOT}/cowork-user"
APP_CONFIG_URL="http://${INFRA_HOST}:8761" APP_PROFILE=local PORT="${PORT}" \
SECRET_KEY_BASE="${SECRET_KEY_BASE}" \
DATABASE_URL="ecto://cowork:${COWORK_MYSQL_PASSWORD}@${INFRA_HOST}:3306/cowork_user" \
DB_JDBC_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
DB_USERNAME=cowork DB_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka/" \
EUREKA_INSTANCE_HOST="${SELF_IP}" EUREKA_INSTANCE_PORT="${PORT}" \
KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
MINIO_INTERNAL_ENDPOINT="http://${INFRA_HOST}:9000" \
MINIO_PUBLIC_ENDPOINT="http://58.125.185.121:9000" \
MINIO_ACCESS_KEY=minioadmin MINIO_SECRET_KEY=minioadmin \
setsid nohup _build/prod/rel/cowork_user/bin/cowork_user start > /tmp/cowork_user_start.log 2>&1 < /dev/null &

echo "[user] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null; then
    echo "[user] healthy"
    exit 0
  fi
  sleep 2
done

echo "[user] FAILED health check after deploy" >&2
tail -n 80 /tmp/cowork_user_start.log >&2 || true
exit 1
