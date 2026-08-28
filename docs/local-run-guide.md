# 로컬 초기 배포 가이드

이 문서는 로컬 DB와 Kafka에 기존 데이터가 없는 상태에서 `cowork-server` 전체를 처음 배포하는
방법을 설명한다. 기존 환경 업그레이드 절차는 다루지 않는다.

기준 구성은 `docker-compose.yml`, `docker-compose.override.yml`, 각 서비스 `Dockerfile`,
`cowork-config/src/main/resources/configs/*-local.yml`이다.

## 1. 준비

- Docker Desktop 또는 Docker Engine
- Docker Compose v2
- 이 저장소의 루트 디렉터리

Java, Go, Node.js, Elixir는 호스트에 설치할 필요가 없다. 모든 애플리케이션은 컨테이너 안에서
빌드된다.

## 2. 로컬 설정 생성

루트에 `.env`를 만든다.

```bash
cp .env.example .env
```

`.env`에서 최소한 다음 값을 채운다. `scripts/run/local/infra.sh`가 기동 전에 이 항목을
검사한다.

| 키                                                    | 용도                                            |
|-------------------------------------------------------|-------------------------------------------------|
| `MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD` | MySQL 기동과 서비스 접속                        |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`                  | PostgreSQL과 `cowork-preference` 접속           |
| `MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD`          | MongoDB와 chat·voice 접속                       |
| `JWT_SECRET`                                          | Gateway JWT 검증과 authorization 발급           |
| `DATAGSM_CLIENT_ID`                                   | authorization 설정 검증                         |
| `ACCOUNT_CREDENTIAL_ENCRYPTION_KEY`                   | channel AccountShare 자격 증명 암호화           |
| `ACCOUNT_SHARE_OAUTH_STATE_SECRET`                    | channel OAuth state 서명                        |
| `TEAM_GITHUB_STATE_SECRET`                            | team GitHub 설치 state 서명 및 서비스 기동 검증 |
| `GITHUB_APP_SLUG`                                     | team GitHub 설치 URL 생성 및 서비스 기동 검증   |

암호화·서명 키는 서로 다른 값으로 생성한다.

```bash
openssl rand -base64 32
```

`GITHUB_APP_INTERNAL_API_KEY`는 보류 중인 GitHub App 연동 HTTP API까지 확인할 때 설정한다.
`TEAM_GITHUB_STATE_SECRET`과 `GITHUB_APP_SLUG`는 해당 API 구현을 이번 작업에서 변경하지 않더라도
현재 `cowork-team`의 기동 계약상 필수다.
`S3_PUBLIC_ENDPOINT`와 `S3_PUBLIC_BASE_URL`에는 클라이언트가 도달할 수 있는 주소를 설정한다.
공개/인증 GET, bucket 분리, public ingress와 signer, CORS, 저장 URL 이관 계약은 아직 확정하지
않았으며 [오브젝트 스토리지 공개 접근 계약 TODO](./todo/items/13-storage/object-storage-public-access-contract.md)에서 관리한다.
`LIVEKIT_WS_URL`은 voice가 클라이언트에 돌려주는 주소다. 브라우저가 같은 호스트에서
실행되면 `ws://localhost:7880`, 실기기에서는 `ws://<호스트 LAN IP>:7880`을 사용한다.

### Firebase credential

`cowork-notification`을 포함한 전체 구성을 올리려면 다음 파일이 실제로 존재해야 한다.

```text
docker/secrets/firebase-credentials.json
```

Compose는 이 파일을 `/run/secrets/firebase-credentials.json`에 read-only secret으로 전달한다.
파일이 없으면 로컬 실행 스크립트가 즉시 종료한다.

## 3. 빈 상태에서 전체 기동

다음 명령은 로컬 Docker volume을 모두 삭제한다. 보존할 로컬 DB·Kafka·파일 데이터가
있다면 실행하지 않는다.

```bash
docker compose down -v --remove-orphans
```

먼저 환경변수 치환과 Compose 병합 결과를 검사한다.

```bash
docker compose config --quiet
```

전체 스택을 기동한다.

```bash
./scripts/run/local/infra.sh start
```

스크립트 이름은 `infra.sh`이지만 실제로는 일반 `docker compose up -d`를 실행해 인프라와
애플리케이션을 모두 올린다. 실행 후 MySQL, PostgreSQL, MongoDB, Kafka, Vault, Redis, SeaweedFS
일곱 core infra 컨테이너가 healthy가 될 때까지만 기다린다. 스크립트 종료는 모든 init job과
애플리케이션의 readiness 완료를 의미하지 않으므로 아래 기동 확인 절차를 이어서 수행한다.

## 4. 첫 기동 시 자동으로 진행되는 일

1. MySQL, PostgreSQL, MongoDB, Kafka, Redis, Vault, SeaweedFS, Elasticsearch가 기동한다.
2. `kafka-init`이 필수 topic을 생성하고 state topic에 log compaction을 설정한다.
3. `vault-init`이 `.env`의 값을 로컬 Vault에 기록하고, `seaweedfs-init`이 bucket과 로컬 CORS를
   준비한다.
4. `cowork-config`가 Config Server와 Eureka로 기동한다.
5. 각 서비스가 자체 마이그레이션을 순서대로 적용하고 로컬 설정을 읽는다.
6. Kafka state source가 빈 DB에서도 topic partition별 `PROJECTION_SNAPSHOT_COMPLETED` marker를
   발행하고, consumer가 자신의 checkpoint와 초기 high-watermark까지 처리한다.

Compose의 `depends_on`이 init job과 필수 서비스의 순서를 조정하므로 개별 컨테이너를 수동으로
먼저 올릴 필요가 없다. `cowork-user`는 healthy한 `cowork-authorization`이 준비된 뒤 시작한다.

State marker는 모두 동시에 생기지 않는다. 빈 상태에서도 upstream projection으로부터 만든 snapshot은
그 upstream의 현재 high-watermark를 확인한 뒤에만 완료된다. 대략 authorization의 presence·`team` state,
`user.profile.event`·`project.event`, `channel.event`, `project.github-repo.event` 순으로 marker가 열리며,
이 인과 순서를 기다리는 동안 dependent service가 `starting`인 것은 정상이다.

### 로컬 기동에 영향을 주는 제약

- `cowork-user`는 Kafka가 유일한 서비스 간 경로다. `KAFKA_ENABLED=false`로는 기동하지 않고 fail-fast 하므로
  로컬에서도 Kafka와 `kafka-init`을 함께 올린다.
- 빈 DB에서는 각 서비스의 Flyway가 source와 projection 테이블을 모두 만들므로 별도 데이터 이관 절차가 없다.
- 별도 `cowork-github-app` 저장소의 프로세스는 이 Compose에 포함되지 않는다. 기본 stack과 Kafka projection은
  그 프로세스 없이 기동한다. GitHub 조직 저장소와 PR 조회까지 확인하려면 github-app을 host `3000`에 별도로
  실행하고 내부 API key를 양쪽에 동일하게 설정한다.
- `github.pr.merge`·`github.pr.approve` topic은 기본 Compose가 provision하지 않는다. 빈 상태에서 해당 API를
  호출하면 실패하며, Broker auto-create에 의존하지 말고 topic을 명시적으로 추가해야 한다.

## 5. 기동 확인

일회성 init job과 장기 실행 서비스를 함께 확인한다.

```bash
docker compose ps --all
```

정상 기준은 다음과 같다.

- `kafka-init`, `vault-init`, `seaweedfs-init`, `alertmanager-config-init` 등의 init job:
  `Exited (0)`
- 장기 실행 애플리케이션과 인프라: `running` 및 `healthy`
- `cowork-user`: presence·team-member projection 준비 후 `healthy`
- `cowork-project`: team·channel·user-profile·GitHub repo setting projection 준비 후 `healthy`
- `cowork-chat`: project GitHub repo를 포함한 필수 projection 준비 후 `healthy`

전체 상태가 준비되는 동안 로그를 확인한다.

```bash
docker compose logs -f --tail=200
```

특정 서비스만 보려면 이름을 지정한다.

```bash
docker compose logs -f cowork-authorization cowork-user cowork-team
```

### Projection snapshot marker 확인

Kafka UI `http://localhost:8090`에서 `user.presence.event`, `team.member.event`,
`channel.event`, `user.profile.event`, `project.github-repo.event`,
`preference.team-role.changed`, `preference.github-repo.setting.state`를 열어
`PROJECTION_SNAPSHOT_COMPLETED`를 확인할 수 있다. CLI로는 다음과 같이 확인한다.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic user.presence.event \
  --from-beginning \
  --timeout-ms 10000 \
  --property print.key=true | grep PROJECTION_SNAPSHOT_COMPLETED
```

다른 state topic은 `--topic` 값만 바꿔 같은 방법으로 확인한다. partition 수가 1보다 크면
모든 partition의 marker가 있어야 한다.

### 주요 접속 주소

| 용도              | URL                                     |
|-------------------|-----------------------------------------|
| Gateway           | `http://localhost:8080`                 |
| Eureka            | `http://localhost:8761`                 |
| Kafka UI          | `http://localhost:8090`                 |
| Vault             | `http://localhost:8200`                 |
| SeaweedFS Console | `http://localhost:9002`                 |
| Elasticsearch     | `http://localhost:9200/_cluster/health` |
| Prometheus        | `http://localhost:9090`                 |
| Grafana           | `http://localhost:3001`                 |

외부 HTTP 요청은 Gateway로 보낸다. 개별 서비스 포트는 로컬 진단에만 사용한다.

## 6. 첫 기동 문제 확인

### 실행 스크립트가 바로 종료됨

`.env`의 필수값과 `docker/secrets/firebase-credentials.json` 존재 여부를 먼저 확인한다.

### init job이 `Exited (1)`

```bash
docker compose logs kafka-init vault-init seaweedfs-init alertmanager-config-init
```

`kafka-init`이 실패하면 필수 topic이 없으므로 애플리케이션을 재시작해도 정상 기동하지
않는다. 먼저 init job의 원인을 해결한 뒤 전체를 다시 올린다.

### `cowork-user`가 `starting` 또는 `unhealthy`

```bash
docker compose logs cowork-authorization cowork-user cowork-team
```

`user.presence.event`, `team.member.event`의 snapshot marker와 consumer checkpoint
처리 오류를 확인한다. 빈 DB에 사용자나 팀이 하나도 없더라도 source는 marker를 발행해야
한다.

로그인 요청만 실패한다면 `user.identity.command`와 `user.identity.command-result`의 key·operation ID,
authorization의 pending operation, user의 command inbox와 result outbox 오류를 함께 확인한다.

### `cowork-project` 또는 `cowork-chat`이 `starting`

```bash
docker compose logs cowork-channel cowork-user cowork-project cowork-chat
```

project는 `channel.event`·`user.profile.event`·`preference.github-repo.setting.state`, chat은
`project.github-repo.event`를 포함한 필수 state topic의 marker와
checkpoint를 확인한다. 저장소 연결이나 설정이 0건인 빈 DB에서도 source completion marker는 필요하다.

### Elasticsearch 기동이 느림

Elasticsearch는 컨테이너에 최소 1 GB 이상의 여유 메모리가 필요하며 healthcheck가 완료될 때까지
수 분이 걸릴 수 있다.

```bash
docker compose logs -f elasticsearch cowork-chat
```

### 외부 기기에서 S3 URL에 접속할 수 없음

`scripts/run/local/infra.sh`는 `.env`의 `S3_PUBLIC_ENDPOINT` 또는 `S3_PUBLIC_BASE_URL`에 있는
`__LOCAL_IP__`를 현재 LAN IP로 치환한다. `docker compose up`를 직접 실행하면 이 치환이
적용되지 않는다. LAN IP를 찾지 못하면 스크립트는 경고만 출력하고 placeholder를 유지하므로,
외부 실기기에서 확인할 때는 `.env`에 LAN IP를 직접 설정한다. 접근 정책과 ingress 문제는
[오브젝트 스토리지 공개 접근 계약 TODO](./todo/items/13-storage/object-storage-public-access-contract.md)를 참고한다.
