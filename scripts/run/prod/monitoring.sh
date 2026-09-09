#!/bin/bash
# cowork-monitoring 배포 스크립트 (VM: cowork-db / 21108)
#
# grafana/prometheus/loki/alertmanager/blackbox 등은 자체 이미지가 없고 설정 파일만
# bind-mount하므로, "배포"는 저장소의 cowork-monitoring/ 설정을 최신으로 맞추고
# 관련 컨테이너를 재시작하는 것이다.
#
# 같은 VM(21108)에서 gateway/config/team도 동시에 배포될 수 있고, 걔들도 같은
# ~/cowork-server 클론에서 스크립트 파일 하나를 checkout한다 — 그 작업트리/인덱스를
# 건드리면 경합이 생긴다. 그래서 여기서는 `git checkout -- <dir>`로 작업트리를 바꾸는
# 대신, 순수 읽기 전용인 `git archive`로 해당 커밋의 cowork-monitoring/ 내용만 뽑아
# 임시 디렉터리에 풀고 rsync로 반영한다 — 클론의 HEAD/인덱스를 전혀 건드리지 않고,
# 저장소에서 지운 파일(rsync --delete)도 서버에서 같이 지워진다.
#
# git 인덱스로 관리하지 않으니, "운영자가 서버에서 직접 설정을 고쳤는지"는 git diff
# 대신 마지막 배포 시점의 트리 해시를 기록해뒀다가 비교하는 방식으로 확인한다 —
# 안 맞으면 조용히 덮어쓰지 않고 실패한다.
set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"

REPO_DIR="${HOME}/cowork-server"
MONITORING_DIR="${REPO_DIR}/cowork-monitoring"
HASH_MARKER="${REPO_DIR}/.monitoring-tree-hash"

tree_hash() {
  find "${MONITORING_DIR}" -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | awk '{print $1}'
}

if [ -f "${HASH_MARKER}" ] && [ -d "${MONITORING_DIR}" ]; then
  RECORDED="$(cat "${HASH_MARKER}")"
  CURRENT="$(tree_hash)"
  if [ "${RECORDED}" != "${CURRENT}" ]; then
    echo "[monitoring] FAILED: cowork-monitoring/이 마지막 배포 이후 서버에서 직접 바뀐 것 같습니다 — 조용히 덮어쓰지 않고 중단합니다" >&2
    echo "[monitoring] 확인 후 저장소에 반영하거나 ${HASH_MARKER}를 지우고 재실행하세요" >&2
    exit 1
  fi
fi

cd "${REPO_DIR}"

echo "[monitoring] fetching ${DEPLOY_SHA}"
git fetch origin --quiet

TMPDIR="$(mktemp -d)"
trap 'rm -rf "${TMPDIR}"' EXIT

echo "[monitoring] exporting cowork-monitoring/ at ${DEPLOY_SHA} (read-only, 다른 서비스 배포와 안전하게 병행 가능)"
git archive "${DEPLOY_SHA}" -- cowork-monitoring | tar -x -C "${TMPDIR}"

echo "[monitoring] syncing into ${MONITORING_DIR} (삭제된 파일도 반영)"
rsync -a --delete "${TMPDIR}/cowork-monitoring/" "${MONITORING_DIR}/"

echo "[monitoring] validating compose config"
docker compose -f docker-compose.yml -f docker-compose.prod.yml config -q

echo "[monitoring] restarting monitoring stack"
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart prometheus grafana loki promtail alertmanager blackbox_exporter

echo "[monitoring] waiting for prometheus health check"
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
if wait_for_health "http://127.0.0.1:9090/-/healthy" 30 2; then
  echo "[monitoring] healthy"
  tree_hash > "${HASH_MARKER}"
  exit 0
fi

echo "[monitoring] FAILED health check after deploy" >&2
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail 50 prometheus grafana >&2 || true
exit 1
