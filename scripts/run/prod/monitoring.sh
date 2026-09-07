#!/bin/bash
# cowork-monitoring 배포 스크립트 (VM: cowork-db / 21108)
#
# grafana/prometheus/loki/alertmanager/blackbox 등은 자체 이미지가 없고 설정 파일만
# bind-mount하므로, "배포"는 저장소의 cowork-monitoring/ 설정을 최신으로 맞추고
# 관련 컨테이너를 재시작하는 것이다.
#
# 서버 작업 디렉터리에 cowork-monitoring/ 관련 커밋되지 않은 변경이 남아있으면
# (과거처럼 운영자가 서버에서 직접 설정을 고친 경우) 조용히 덮어쓰지 않고 실패한다 —
# git reset --hard/자동 stash로 운영 설정을 유실시키지 않기 위함. 사람이 git diff로
# 확인하고 저장소에 반영하거나 정리한 뒤 재실행해야 한다.
set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"

REPO_DIR="${HOME}/cowork-server"
cd "${REPO_DIR}"

if ! git diff --quiet -- cowork-monitoring || ! git diff --cached --quiet -- cowork-monitoring; then
  echo "[monitoring] FAILED: uncommitted local changes under cowork-monitoring/ — resolve manually first:" >&2
  git status --short -- cowork-monitoring >&2
  exit 1
fi

echo "[monitoring] fetching and checking out ${DEPLOY_SHA}"
git fetch origin --quiet
git checkout "${DEPLOY_SHA}" -- cowork-monitoring

echo "[monitoring] validating compose config"
docker compose config -q

echo "[monitoring] restarting monitoring stack"
docker compose restart prometheus grafana loki promtail alertmanager blackbox_exporter

echo "[monitoring] waiting for prometheus health check"
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:9090/-/healthy" >/dev/null; then
    echo "[monitoring] healthy"
    exit 0
  fi
  sleep 2
done

echo "[monitoring] FAILED health check after deploy" >&2
docker compose logs --tail 50 prometheus grafana >&2 || true
exit 1
