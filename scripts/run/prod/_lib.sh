#!/bin/bash
# scripts/run/prod/*.sh가 공용으로 source하는 헬퍼 모음.
# (PR #352 리뷰에서 지적된 "hostname -I 불안정", "rm 후 run이라 실패 시 다운타임" 문제를
# 스크립트마다 따로 고치지 않고 한 곳에서 고치기 위해 분리함.)

# 지정한 목적지로 나가는 실제 라우팅 소스 IP를 구한다. `hostname -I`는 인터페이스
# 순서를 보장하지 않아 docker0(보통 172.17.0.1)이 먼저 잡히는 경우가 있고, 그 값이
# Eureka에 등록되면 다른 VM의 서비스가 이 서비스를 못 찾는다. 실제로 그 목적지까지
# 나갈 때 커널이 고르는 소스 IP를 물어보는 쪽이 안전하다.
advertise_ip() {
  local peer_host="$1"
  ip -4 route get "${peer_host}" | awk '{print $7; exit}'
}

# url이 성공(2xx) 응답할 때까지 재시도. 실패하면 1을 리턴(exit은 호출자 책임).
wait_for_health() {
  local url="$1" retries="${2:-45}" delay="${3:-2}"
  local i
  for ((i = 0; i < retries; i++)); do
    if curl -sf "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${delay}"
  done
  return 1
}

# 헬스체크 실패 시 서비스가 내려간 채로 남는 문제(리뷰 지적)를 없애기 위해, 새
# 컨테이너를 임시 이름+임시 호스트 포트로 먼저 띄워 헬스체크를 통과한 뒤에만 기존
# 컨테이너를 내리고 실제 이름/포트로 다시 올린다. 임시 컨테이너가 끝내 안 뜨면
# 기존 컨테이너는 손대지 않고 그대로 서비스를 계속한다.
#
# 사용법: deploy_container_safely <container> <host_port> <container_port> <health_path> <image> [-- docker run 추가 인자...]
# 마지막 "--" 뒤 인자들은 -e/-​-network 등 -p/--name/이미지를 제외한 나머지 docker run 인자다.
deploy_container_safely() {
  local container="$1" host_port="$2" container_port="$3" health_path="$4" image="$5"
  shift 5
  if [ "${1:-}" = "--" ]; then shift; fi
  local extra_args=("$@")

  local temp_name="${container}-new"
  local temp_port=$(( (RANDOM % 20000) + 40000 ))

  docker rm -f "${temp_name}" >/dev/null 2>&1 || true
  docker run -d --name "${temp_name}" \
    -p "${temp_port}:${container_port}" \
    "${extra_args[@]}" \
    "${image}"

  echo "[deploy] verifying new image on temp port ${temp_port} before touching ${container}"
  if ! wait_for_health "http://127.0.0.1:${temp_port}${health_path}" 45 2; then
    echo "[deploy] FAILED: new image never became healthy — ${container} is untouched and keeps serving" >&2
    docker logs --tail 50 "${temp_name}" >&2 || true
    docker rm -f "${temp_name}" >/dev/null 2>&1 || true
    return 1
  fi

  echo "[deploy] new image verified healthy — swapping ${container} in"
  docker rm -f "${temp_name}" >/dev/null 2>&1 || true
  docker rm -f "${container}" >/dev/null 2>&1 || true
  docker run -d --name "${container}" --restart unless-stopped \
    -p "${host_port}:${container_port}" \
    "${extra_args[@]}" \
    "${image}"

  if wait_for_health "http://127.0.0.1:${host_port}${health_path}" 45 2; then
    echo "[deploy] ${container} healthy on ${host_port}"
    docker image prune -f >/dev/null 2>&1 || true
    return 0
  fi

  echo "[deploy] FAILED: ${container} unhealthy after swap to the real port" >&2
  docker logs --tail 50 "${container}" >&2 || true
  return 1
}

# ghcr.io 패키지가 private일 수 있어(리뷰 지적), GHCR_READ_TOKEN이 주어지면 로그인한다.
# 없으면 패키지가 public이라는 전제로 그냥 pull을 시도한다.
ghcr_login_if_needed() {
  if [ -n "${GHCR_READ_TOKEN:-}" ]; then
    echo "${GHCR_READ_TOKEN}" | docker login ghcr.io -u "${DEPLOY_IMAGE_OWNER}" --password-stdin
  fi
}
