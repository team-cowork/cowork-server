# 운영 배포 전환과 복구

운영 서비스는 여러 VM에 나뉘어 있다. SSH 접속 주소와 서비스 간 통신 주소는 다르며,
Docker 네트워크·볼륨은 VM 사이에서 공유되지 않는다. 실제 VM 주소·기존 볼륨·프로세스 상태는
저장소만으로 확정할 수 없으므로 최초 적용 전에 아래 정보를 확인한다.
전환 진행 상황은 [운영 전환 TODO](todo/items/42-deployment/multi-vm-rollout.md)에서 관리한다.

## 최초 적용 준비

각 `Prod-CD(<service>)` GitHub Environment의 `COWORK_SSH_HOST`, `CD_DEPLOY_SSH_KEY`를
확인한다. SSH 포트는 `deploy/prod/inventory.json`의 기존 값을 유지하며, VM 이동 시
`COWORK_SSH_PORT`·`COWORK_SSH_USER` variables로 덮어쓸 수 있다.
VM에는 Docker Engine, Compose 2.24.4 이상, Bash, curl, iproute2, flock, Git, Python 3가 필요하다.

배포 계정으로 checkout 루트에서 필요한 양식만 복사한다. 기존 파일은 덮어쓰지 않는다.

```bash
sudo install -d -m 700 -o "$(id -un)" -g "$(id -gn)" /etc/cowork
test -e /etc/cowork/common.env || install -m 600 deploy/prod/env/common.env.example /etc/cowork/common.env
service=project  # 이 VM에 배포할 앱 이름으로 변경
test -e "/etc/cowork/$service.env" || install -m 600 deploy/prod/env/service.env.example "/etc/cowork/$service.env"
```

복사한 파일의 예시 주소를 실제 값으로 수정한다. `common.env`와 `<service>.env`는 신뢰하는
운영자가 관리하는 Bash 변수 대입 파일이며, CI 입력보다 VM 파일의 값이 우선한다.
`ADVERTISE_IP`에는 Gateway·모니터링 VM에서 도달 가능한 이 VM의 사설 IP를 지정한다.
다중 NIC·VPN 환경에서는 자동 IP 추정에 의존하지 않는다.

- Config·Kafka·DB·Redis·S3·LiveKit은 각각 실제 접속 주소를 지정한다. 같은 VM에서도
  기존 컨테이너 DNS만 사용하던 인프라는 사설 IP와 포트로 접근할 수 있어야 한다.
  Kafka advertised listener와 VM 방화벽도 이 주소에 맞춘다.
- `config_profile: local`은 기존 CD 상태를 보존한 값이다. VM 파일에서 `APP_CONFIG_PROFILE`을
  지정하면 이 값을 덮어쓴다. 수동 실행의 기본값은 `prod`이므로 최초 점검에서도 프로파일을 명시한다.
  `prod` 전환은 [설정 가이드](configuration.md)의 공개 origin·Config override·Vault 키를 준비한 뒤
  Config Server, 클라이언트 순으로 진행한다. 최초 전환 순서는 자동 병렬 배포에 맡기지 않는다.
- 기존 DB·Vault 백업과 접속 주소를 기록한다. S3 자격 증명은 기존 운영 값을 Vault 또는 서비스
  환경 파일에 넣는다. 이 변경은 DB나 MinIO 데이터를 새 저장소로 이관하지 않는다.
- notification의 `FIREBASE_CREDENTIALS`는 해당 VM에 존재하는 절대 경로로 지정한다.

## 기존 프로세스와 데이터 유지

| 대상 | 최초 전환 시 확인할 사항 |
| --- | --- |
| user | 기존 네이티브 Elixir 프로세스와 자동 재기동 관리자를 중지한 뒤 컨테이너로 전환한다. 기존 Flyway 이력·DB 접속값을 확인하고 네이티브 릴리스 복구 방법을 보관한다. 첫 전환에는 자동 복구할 이전 Docker 컨테이너가 없다. |
| monitoring | 기존 Prometheus·Grafana·Loki 볼륨의 실제 이름을 확인해 `MONITORING_VOLUME_PREFIX`를 지정한다. 기존 모니터링 컨테이너만 중지하고 새 프로젝트로 기동한다. 세 볼륨의 접두사가 다르면 Compose의 명시적 `name`을 실제 이름에 맞춘다. |
| Vault | 기존 `cowork-vault-prod`의 데이터 볼륨 이름을 `VAULT_DATA_VOLUME`에 지정한다. 기존 `vault` 프로젝트 이름을 유지한다. 기존 설치에서 새 빈 볼륨을 만들거나 local seed를 실행하지 않는다. |

볼륨 이름은 컨테이너의 환경변수나 시크릿을 출력하지 않고 확인한다.

```bash
docker inspect --format '{{range .Mounts}}{{println .Destination .Name .Source}}{{end}}' cowork-vault-prod
docker ps --format '{{.Names}}'  # 기존 모니터링 컨테이너 이름 확인
# 위 inspect 명령의 컨테이너 이름을 각 Prometheus·Grafana·Loki 컨테이너로 바꿔 확인
```

Vault의 TLS 종단 프록시만 Vault 사설 포트에 접근하도록 제한한다. 최초 초기화와 unseal key
보관은 운영자가 수행한다. 현재 CD의 단일 unseal key 입력은 기존 1-share/1-threshold 환경을
전제로 하므로 다중 share 환경은 별도 unseal 절차가 필요하다.
모니터링과 Vault는 앱 자동 롤백 대상이 아니므로 업그레이드 전 백업·복구 절차를 준비한다.
기존 데이터가 있는 Compose 프로젝트에서 이름을 임의로 바꾸거나 `down -v`를 실행하지 않는다.

## 서비스별 점검과 적용

CI가 빌드한 커밋의 checkout 또는 `~/.local/share/cowork/releases/<sha>`에서 실행한다.
VM의 환경 파일을 먼저 수정하고, 현재 운영 프로파일로 `check` 결과를 확인한 뒤 적용한다.

```bash
export DEPLOY_SHA="$(git rev-parse HEAD)"  # checkout에서 실행; 릴리스 디렉터리는 cat .source-sha 사용
export DEPLOY_IMAGE_OWNER=team-cowork DEPLOY_IMAGE_TAG="sha-$DEPLOY_SHA"
export APP_CONFIG_PROFILE=local          # prod 준비 완료 후 변경
bash deploy/prod/deploy.sh project check
# 위 결과와 VM 간 접근을 확인한 뒤 실행
bash deploy/prod/deploy.sh project
```

`check`는 설정 검증이며 네트워크 연결이나 서비스 기동 성공을 보장하지 않는다.
chat·roadmap은 projection readiness가 열려야 배포가 성공한다. 기존 데이터 복구 문제가 있으면
liveness로 우회하지 않고 원인을 먼저 해결한다.
readiness 기본 대기는 420초이며 `HEALTH_TIMEOUT_SECONDS`로 조정한다. 늘릴 때는 CI의
SSH `command_timeout`에 이미지 pull과 실패 후 복구 시간까지 확보한다. 상태 토픽 v2의
기존 데이터 전환은 [별도 유지보수 절차](kafka-state-topic-cutover.md)에 따라 진행한다.

별도 단일 VM 설치만 `./deploy/compose.sh single-vm-prod`를 사용한다.
`COMPOSE_ENV_FILE`은 [양식](../deploy/compose/single-vm.prod.env.example)을 채운 절대 경로,
`COMPOSE_PROJECT_NAME`은 기존 데이터 볼륨을 생성한 프로젝트 이름으로 지정한다.

앱 VM마다 `deploy/prod/env/log-agent.env.example`을 `/etc/cowork/log-agent.env`로 복사해
`LOG_HOST`와 중앙 `LOKI_PUSH_URL`을 지정한다. Docker data-root가 다르면
`DOCKER_CONTAINER_LOG_DIR`도 수정한다. 그 뒤 각 VM에서 한 번 실행하며 설정 변경 시 재적용한다.

```bash
export LOG_AGENT_ENV_FILE=/etc/cowork/log-agent.env
bash deploy/prod/log-agent/run.sh config --quiet
bash deploy/prod/log-agent/run.sh up -d
```

기존 Promtail은 Alloy 전환 시 중지한다. 읽기 위치 형식이 달라 남아 있던 로그가 재전송될 수 있다.
서비스별 로그 필드 정규화와 수집 누락은 [로그 수집 TODO](todo/items/43-monitoring/log-collection-contract.md)에서 관리한다.

## 실패 복구

앱 교체에는 짧은 중단이 있다. readiness 실패 시 이전 컨테이너를 재시작하지만 DB migration과
외부 부작용은 되돌리지 않으므로 이전 이미지와 호환 가능한 스키마 변경이 필요하다.
여러 VM 배치는 복제나 무중단 배포를 보장하지 않는다.

전원 장애·SIGKILL 후 `*-candidate`·`*-previous`가 남으면 다음 배포는 중단된다.
Docker 상태와 포트 점유를 확인해 이전 컨테이너를 복원하거나 잔여 컨테이너를 정리한다.
이전 컨테이너의 IP·포트가 새 설정과 다르면 복원 후 원래 주소로 상태를 확인한다.

수동 롤백은 이전 SHA의 파일 묶음에서 같은 SHA의 이미지 태그로 배포한다. 실행 중 컨테이너의
bind mount가 참조하는 릴리스와 복구용 릴리스는 삭제하지 않는다.
디스크 보존 정책의 자동화는 [릴리스 정리 TODO](todo/items/44-deployment/release-retention.md)로 분리한다.
