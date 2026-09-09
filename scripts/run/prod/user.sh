#!/bin/bash
# cowork-user 배포 스크립트 (VM: 21133) — 다른 8개와 달리 Docker가 아니라 Elixir
# 네이티브 릴리즈로 돈다. CD의 build-images job이 cowork-user Docker 이미지도
# 만들지만, 이 서비스는 실제로 그 이미지를 쓰지 않는다(서버에서 소스로 직접
# mix release 빌드) — 이 스크립트는 그 기존 방식을 그대로 저장소로 옮긴 것이며,
# Docker 기반으로 바꾸는 건 이번 스코프가 아니다(검증 안 된 별도 변경이라 위험).
#
# git pull 대신 CD가 넘겨준 정확한 SHA로 checkout해서, 다른 서비스들과 동일하게
# "이 배포가 어떤 커밋을 반영했는지"가 결정적으로 고정되게 한다.
#
# 새 릴리스를 임시 포트로 먼저 띄워 헬스체크를 통과했을 때만 기존 프로세스를 내리고
# 실제 포트로 다시 올린다(다른 서비스들의 deploy_container_safely와 같은 이유 —
# 새 빌드가 실제로 안 죽고 뜨는지 확인하기 전엔 기존 프로세스를 건드리지 않는다).
set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

REPO_ROOT="$HOME/cowork-server"
INFRA_HOST="10.0.0.93"
PORT=8082
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

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

start_release() {
  local port="$1"
  cd "${REPO_ROOT}/cowork-user"
  APP_CONFIG_URL="http://${INFRA_HOST}:8761" APP_PROFILE=local PORT="${port}" \
  SECRET_KEY_BASE="${SECRET_KEY_BASE}" \
  DATABASE_URL="ecto://cowork:${COWORK_MYSQL_PASSWORD}@${INFRA_HOST}:3306/cowork_user" \
  DB_JDBC_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  DB_USERNAME=cowork DB_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka/" \
  EUREKA_INSTANCE_HOST="${SELF_IP}" EUREKA_INSTANCE_PORT="${port}" \
  KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  MINIO_INTERNAL_ENDPOINT="http://${INFRA_HOST}:9000" \
  MINIO_PUBLIC_ENDPOINT="http://58.125.185.121:9000" \
  MINIO_ACCESS_KEY=minioadmin MINIO_SECRET_KEY=minioadmin \
  setsid nohup _build/prod/rel/cowork_user/bin/cowork_user start > /tmp/cowork_user_start.log 2>&1 < /dev/null &
  LAST_PID=$!
}

# beam.smp가 setsid/nohup으로 감싸져 있어서 실제 beam 프로세스는 위에서 캡처한
# 쉘 PID와 다를 수 있다 — 그 자식 중 cowork_user beam.smp를 찾아 종료한다.
stop_release_tree() {
  local shell_pid="$1"
  local beam_pid
  beam_pid=$(pgrep -P "${shell_pid}" -f 'beam.smp' || true)
  [ -z "${beam_pid}" ] && beam_pid="${shell_pid}"
  kill -TERM "${beam_pid}" 2>/dev/null || true
}

TEMP_PORT=$(( (RANDOM % 20000) + 40000 ))
echo "[user] starting new release on temp port ${TEMP_PORT} to verify before touching the running instance"
start_release "${TEMP_PORT}"
TEMP_SHELL_PID="${LAST_PID}"

if wait_for_health "http://127.0.0.1:${TEMP_PORT}/actuator/health/readiness" 45 2; then
  echo "[user] new release verified healthy — stopping temp instance and swapping in"
  stop_release_tree "${TEMP_SHELL_PID}"

  OLD_PID=$(pgrep -f 'erts.*bin/beam.smp.*cowork_user' || true)
  if [ -n "${OLD_PID}" ]; then
    echo "[user] stopping previous release (pid ${OLD_PID})"
    kill -TERM "${OLD_PID}"
    sleep 3
  fi

  start_release "${PORT}"
  if wait_for_health "http://127.0.0.1:${PORT}/actuator/health/readiness" 45 2; then
    echo "[user] healthy on ${PORT}"
    exit 0
  fi
  echo "[user] FAILED: unhealthy after swap to the real port" >&2
  tail -n 80 /tmp/cowork_user_start.log >&2 || true
  exit 1
fi

echo "[user] FAILED: new release never became healthy on temp port — existing instance is untouched and keeps serving" >&2
tail -n 80 /tmp/cowork_user_start.log >&2 || true
stop_release_tree "${TEMP_SHELL_PID}"
exit 1
