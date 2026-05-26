# 로컬 실행 가이드

이 문서는 `cowork-server`를 로컬에서 실제로 띄우기 위한 기준 문서다.

기준일:
- 2026-04-27 (초안)
- 2026-04-27 (Docker 통합 실행으로 전면 개정)
- 2026-04-28 (vault-init 추가, gateway JWT_SECRET 주입 수정, authorization USER_SERVICE_URL 수정)
- 2026-05-02 (`cowork-user` Elixir 전환, Flyway 자동 실행, healthcheck 검증 반영)
- 2026-05-11 (Elasticsearch 검색 인덱싱 추가, 로컬 배포 고려 사항 보완)
- 2026-05-26 (ACCOUNT_CREDENTIAL_ENCRYPTION_KEY·ACCOUNT_SHARE_OAUTH_STATE_SECRET 누락 보완, cowork-chat cold-start 경쟁 조건 주의사항 추가, Docker Desktop 재시작 후 Vault 복구 절차 및 gateway 401 원인 추가)

검증 기준:
- `docker-compose.yml`
- 각 서비스의 `Dockerfile`
- 각 서비스의 실제 설정 파일

## 1. 한눈에 보기

**모든 서비스가 `docker compose up -d`로 올라간다.** 인프라와 애플리케이션을 분리할 필요 없다.

| 서비스                    |     포트 | 기술                                   | 상태 |
|------------------------|-------:|--------------------------------------|----|
| `cowork-config`        | `8761` | Spring Boot (Config Server + Eureka) | 정상 |
| `cowork-gateway`       | `8080` | Spring Boot (Cloud Gateway)          | 정상 |
| `cowork-authorization` | `8081` | Go                                   | 정상 |
| `cowork-preference`    | `9001` | Kotlin Vert.x                        | 정상 |
| `cowork-user`          | `8082` | Elixir                               | 정상 |
| `cowork-team`          | `8085` | Spring Boot                          | 정상 |
| `cowork-channel`       | `8083` | Spring Boot                          | 정상 |
| `cowork-notification`  | `8086` | Go                                   | 정상 |
| `cowork-chat`          | `8087` | NestJS                               | 정상 |
| `cowork-voice`         | `8084` | Go                                   | 정상 |
| `cowork-project`       | `8089` | Spring Boot                          | 정상 |

인프라 (DB, 브로커, 모니터링 등) 17개 컨테이너도 동일하게 올라간다.

## 2. 사전 준비

- Docker / Docker Compose v2 이상

그 외 런타임(Java, Go, Node.js)은 **필요 없다**. 모두 컨테이너 안에서 빌드한다.

## 3. 환경변수

루트에서 `.env` 파일을 만든다.

```bash
cp .env.example .env
```

기본값으로 채워진 항목이 많으니 대부분 수정 없이 쓸 수 있다.
아래 항목만 운영/외부 서비스 연동 시 채운다.

| 키                       | 기본값                              | 필요한 경우                                |
|-------------------------|----------------------------------|---------------------------------------|
| `LIVEKIT_API_KEY`                    | `devkey`                    | 로컬 LiveKit / voice 기본값                |
| `LIVEKIT_API_SECRET`                 | `devsecret`                 | 로컬 LiveKit / voice 기본값                |
| `LIVEKIT_CONFIG_FILE`                | `livekit.yaml`              | 클라우드·운영 환경에서 `livekit-cloud.yaml`로 변경 |
| `OAUTH2_GOOGLE_*`                    | 비어 있음                       | Google OAuth2 사용 시                    |
| `OAUTH2_GITHUB_*`                    | 비어 있음                       | GitHub OAuth2 사용 시                    |
| `MINIO_PUBLIC_ENDPOINT`              | `http://__LOCAL_IP__:9000`  | 모바일 앱·외부 기기에서 파일 접근 시 실제 IP로 수정       |
| `ELASTICSEARCH_URL`                  | `http://elasticsearch:9200` | 운영 환경에서 관리형 ES 클러스터 주소로 변경            |
| `ACCOUNT_CREDENTIAL_ENCRYPTION_KEY`  | 비어 있음                       | **필수** — `openssl rand -base64 32`로 생성, 없으면 vault-init이 빈 값으로 시드 |
| `ACCOUNT_SHARE_OAUTH_STATE_SECRET`   | 비어 있음                       | **필수** — `openssl rand -base64 32`로 생성, 없으면 vault-init이 빈 값으로 시드 |

Firebase 관련:
- `docker/secrets/firebase-credentials.json` 파일이 실제로 있어야 `cowork-notification`이 기동된다.
- 파일이 없으면 `cowork-notification` 컨테이너는 시작 단계에서 실패한다.

## 4. Compose 파일 구성

| 파일                            | 역할                     | 적용 방식                           |
|-------------------------------|------------------------|---------------------------------|
| `docker-compose.yml`          | 인프라 + 앱 서비스 공통 정의      | 항상 적용                           |
| `docker-compose.override.yml` | 로컬 개발용 `build:` 설정     | `docker compose` 명령 시 **자동 병합** |
| `docker-compose.prod.yml`     | 운영 레지스트리 이미지 + prod 설정 | `-f` 플래그로 명시                    |

## 5. 실행

### 로컬 개발

```bash
# 전체 기동
# __LOCAL_IP__가 들어간 MinIO 공개 주소를 현재 LAN IP로 자동 치환한다.
./scripts/run/local/infra.sh start

# 앱 서비스는 필요할 때 개별 재기동
./scripts/run/local/user.sh restart

# 특정 서비스만 재빌드
docker compose up -d --build cowork-user
```

### 운영 배포

```bash
export REGISTRY=ghcr.io/your-org
export IMAGE_TAG=v1.2.3
export SPRING_PROFILES_ACTIVE=prod

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### 상태 / 로그

```bash
docker compose ps

# 전체 로그
docker compose logs -f

# 특정 서비스
docker compose logs -f cowork-gateway
```

### 중지 / 초기화

```bash
# 중지
docker compose down

# 볼륨까지 초기화 (DB 데이터 삭제)
docker compose down -v && docker compose up -d
```

## 6. 서비스 기동 순서

`depends_on`이 설정돼 있어서 수동으로 순서를 맞출 필요 없다.
Docker Compose가 아래 의존 관계를 따라 자동으로 기동한다. Spring Boot 이외 서비스도 로컬에서는 `Config Server -> Vault` 경로로 같은 기준의 설정을 받는다.

```
infra (MySQL, Kafka, Redis, Mongo, Postgres, ...)
  ├─ vault → vault-init (시크릿 시드)
  ├─ minio → minio-init (버킷 생성)
  └─ cowork-config (Config Server + Eureka)
       ├─ cowork-gateway
       ├─ cowork-authorization
       ├─ cowork-preference
       ├─ cowork-user
       ├─ cowork-team
       ├─ cowork-channel
       │    └─ cowork-voice (+ redis, livekit, mongodb, kafka)
       ├─ cowork-notification (+ preference, user, team)
       └─ cowork-chat (+ mongodb, kafka)
```

## 7. 빌드 구조

### JVM/Elixir 서비스 (config, gateway, channel, team, preference, user)

- 빌드 컨텍스트: 프로젝트 루트 (`.`)
- `cowork-user`는 Mix release로 빌드
- `cowork-user`는 컨테이너 시작 시 `docker-entrypoint.sh`에서 `flyway migrate`를 먼저 실행한 뒤 Elixir release를 기동
- 나머지 JVM 서비스는 각 서비스별 Gradle 빌드 사용

### Go 서비스 (authorization, notification, voice)

- 빌드 컨텍스트: 각 서비스 디렉터리
- 베이스 이미지: `golang:1.25-alpine` (빌드) → `alpine:3.20` (런타임)

### NestJS 서비스 (chat)

- 빌드 컨텍스트: `cowork-chat/`
- `npm run build` (tsc) → `node dist/main.js`
- 베이스 이미지: `node:22-alpine`

## 8. 컨테이너 간 네트워크

Docker Compose 내부에서 서비스는 컨테이너 이름으로 통신한다.

| 호스트에서 접근                    | 컨테이너 내부 주소              |
|-----------------------------|-------------------------|
| `localhost:3306`            | `mysql:3306`            |
| `localhost:5432`            | `postgres:5432`         |
| `localhost:27017`           | `mongodb:27017`         |
| `localhost:9094` (Kafka 외부) | `kafka:9092` (Kafka 내부) |
| `localhost:6379`            | `redis:6379`            |
| `localhost:9000`            | `minio:9000`            |
| `localhost:9200`            | `elasticsearch:9200`    |
| `localhost:8761`            | `cowork-config:8761`    |

Kafka는 외부 접근용(`9094`)과 컨테이너 내부용(`9092`) 리스너가 분리돼 있다.

## 9. 클라우드 / 운영 환경 전환

| 항목                  | 로컬 값                                      | 운영 변경 사항                                              |
|---------------------|-------------------------------------------|-------------------------------------------------------|
| LiveKit config      | `livekit.yaml` (`use_external_ip: false`) | `.env`에서 `LIVEKIT_CONFIG_FILE=livekit-cloud.yaml`로 변경 |
| LiveKit 키           | `devkey` / `devsecret`                    | 실제 키/시크릿으로 교체                                         |
| DB 비밀번호             | `1234`                                    | 강도 높은 비밀번호로 교체                                        |
| Spring profile      | `local`                                   | `dev` 또는 `prod` (Vault 연동)                            |
| MinIO               | 로컬 컨테이너                                   | S3 호환 엔드포인트로 변경                                       |
| `ELASTICSEARCH_URL` | `http://elasticsearch:9200`               | `.env`에서 관리형 ES 클러스터 주소로 교체 (Vault 경유 주입)             |

### Vault 시크릿 배포 구조

`local`/`dev`/`prod` 프로파일 모두에서 Config Server가 Vault와 native 설정을 합쳐 내려준다. Spring Boot 이외 애플리케이션도 같은 기준으로 이 값을 읽는다.

| 서비스 유형                                        | 시크릿 경로                      |
|-----------------------------------------------|-----------------------------|
| Spring Boot                                   | Vault → Config Server → 서비스 |
| Go (`authorization`, `notification`, `voice`) | Vault → Config Server → 서비스 |
| Elixir (`user`)                               | Vault → Config Server → 서비스 |
| Kotlin (`preference`)                         | Vault → Config Server → 서비스 |
| NestJS (`chat`)                               | Vault → Config Server → 서비스 |

`vault-init` 컨테이너가 Vault 기동 직후 `secret/application` 경로에 아래 시크릿을 자동으로 기록한다.

| Vault 키                                            | 참조하는 서비스                 |
|----------------------------------------------------|--------------------------|
| `JWT_SECRET`                                       | gateway (jwt.secret)     |
| `MYSQL_USER`, `MYSQL_PASSWORD`                     | notification (DB DSN)    |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`               | preference (DB)          |
| `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`             | user, preference (MinIO) |
| `MINIO_PUBLIC_ENDPOINT`, `MINIO_INTERNAL_ENDPOINT` | user, preference (MinIO) |

추가로 서비스별 Vault 경로도 같이 시드된다.

| Vault 경로                      | 주요 키                                                   |
|-------------------------------|--------------------------------------------------------|
| `secret/cowork-authorization` | `DB_DSN`, `DATAGSM_CLIENT_ID`, `JWT_SECRET`            |
| `secret/cowork-notification`  | `db.dsn`                                               |
| `secret/cowork-voice`         | `MONGODB_URI`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |
| `secret/cowork-chat`          | `MONGODB_URI`, `ELASTICSEARCH_URL`                     |

`.env`에서 `SPRING_PROFILES_ACTIVE=dev`로 변경하면 config server가 Vault composite 모드로 전환된다. `VAULT_HOST`는 `cowork-vault`로 자동 설정된다.

## 10. 자주 쓰는 확인 포인트

| 서비스                   | URL                                          |
|-----------------------|----------------------------------------------|
| Eureka Dashboard      | `http://localhost:8761`                      |
| Gateway Swagger       | `http://localhost:8080/swagger-ui.html`      |
| Kafka UI              | `http://localhost:8090`                      |
| Prometheus            | `http://localhost:9090`                      |
| Grafana               | `http://localhost:3001`                      |
| MinIO Console         | `http://localhost:9002`                      |
| Vault                 | `http://localhost:8200`                      |
| Elasticsearch 클러스터 상태 | `http://localhost:9200/_cluster/health`      |
| chat_messages 인덱스 확인  | `http://localhost:9200/chat_messages/_count` |
| 인덱스 목록                | `http://localhost:9200/_cat/indices?v`       |

## 11. 알려진 주의사항

`cowork-config`:
- `local`/`dev`/`prod` 모든 프로파일에서 Vault + native composite 모드로 동작한다.
- `vault-init` 완료 후 기동하도록 `depends_on`이 설정돼 있다. `VAULT_HOST=cowork-vault`는 docker-compose에 이미 설정돼 있다.

`cowork-gateway`:
- `jwt.secret`은 모든 프로파일에서 Vault → config server 경로로 주입된다.

`cowork-authorization`:
- Docker Compose 로컬 실행 시 `APP_CONFIG_URL`/`APP_PROFILE`로 Config Server 설정을 읽는다.
- `DB_DSN`, `DATAGSM_CLIENT_ID`, `JWT_SECRET`는 Vault `secret/cowork-authorization`에서 내려온다.

`cowork-user`:
- Docker healthcheck는 `GET /actuator/health`를 사용하며, 현재 로컬 Compose 기준 `healthy` 확인됨.
- Config Server 연동은 `APP_CONFIG_URL`, `APP_PROFILE` 기반이며 `SPRING_CONFIG_IMPORT`에 의존하지 않는다.
- DB 연결은 `DATABASE_URL`과 Flyway용 `DB_JDBC_URL`/`DB_USERNAME`/`DB_PASSWORD`를 사용한다.
- Eureka 주소는 `EUREKA_SERVER_URL` 또는 config-server의 `eureka_server_url` 설정으로 내려간다.
- Eureka 인스턴스 호스트는 compose에서 `cowork-user:8082`로 고정해 Gateway와 다른 서비스가 service discovery 결과를 그대로 사용할 수 있다.
- 시작 시 Flyway가 기존 `V1`~`V4`를 검사하고, 스키마가 비어 있으면 자동 적용한 뒤 앱이 올라온다.
- Kafka consumer는 `brod` 기반으로 `user.data.sync`를 소비하며, 로컬에서는 해당 토픽 생성 후 실제 upsert까지 검증했다.

`cowork-voice`:
- 로컬 기본 키는 `.env`의 `devkey` / `devsecret`이며 LiveKit 컨테이너와 `cowork-voice`가 동일 값을 공유한다.
- `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `MONGODB_URI`는 Vault `secret/cowork-voice`에서 내려온다.
- Redis 연결은 Config Server 기준 `redis:6379`를 사용한다.

`cowork-notification`:
- `docker/secrets/firebase-credentials.json`이 없으면 기동 자체가 실패한다.

`elasticsearch`:
- 이미지: `docker.elastic.co/elasticsearch/elasticsearch:8.13.4` + nori 형태소 분석기 플러그인 (빌드 시 설치)
- `discovery.type=single-node`, `xpack.security.enabled=false` (로컬 전용, 인증 없음)
- 힙 메모리: `ES_JAVA_OPTS=-Xms512m -Xmx512m` → 컨테이너에 **최소 1 GB RAM** 여유 필요
- 포트: `9200` (호스트에서 `localhost:9200`으로 직접 접근 가능)
- 데이터 볼륨: `es_data` — `docker compose down -v` 실행 시 인덱스가 삭제되며, 다음 기동 시 `cowork-chat`이 자동으로 `chat_messages` 인덱스를 재생성함
- `cowork-chat`이 healthy 상태가 될 때까지 `depends_on`으로 보장; ES healthcheck는 최대 150초(interval 15s × retries 10) 대기

`cowork-chat` (Elasticsearch 관련):
- Docker Compose 로컬 실행 시 시작 전에 Config Server에서 설정을 받아 `process.env`에 로드한다.
- `MONGODB_URI`는 Vault `secret/cowork-chat`, MinIO 자격증명은 `secret/application`에서 공급된다.
- `ELASTICSEARCH_URL`은 Vault `secret/cowork-chat`을 통해 Config Server → 앱 순서로 주입된다. 로컬 기본값은 `http://elasticsearch:9200`이며, 운영 환경에서는 `.env`의 `ELASTICSEARCH_URL`을 실제 클러스터 주소로 교체하면 vault-init이 해당 값을 시드한다.
- **인덱싱 대상**: `projectId`가 있는 메시지만 ES에 인덱싱된다. DM·비프로젝트 채널 메시지는 인덱싱되지 않는다.
- **인덱스 자동 생성**: 앱 기동 시 `OnModuleInit`에서 `chat_messages` 인덱스가 없으면 자동 생성한다. nori 분석기와 `createdAt` + `messageId` 복합 정렬이 기본 설정된다.
- **커서 형식**: 검색 페이지네이션의 `nextCursor`는 ES `sort` 배열(`[createdAt, messageId]`)을 `base64(JSON.stringify(...))` 인코딩한 불투명 문자열이다. 이전 방식(messageId 단순 문자열)과 **호환되지 않으므로** 기존 커서를 가진 클라이언트는 재조회가 필요하다.
- ES 기동이 느릴 경우(`start_period: 60s`): `docker compose logs -f elasticsearch`로 상태 확인 후 `cowork-chat` 재시작
- **cold-start Kafka 경쟁 조건**: `cowork-chat`의 Kafka consumer는 모듈 초기화 시 `subscribe()`를 호출한다. 최초 `docker compose up` 시 다른 서비스가 Kafka 토픽을 아직 생성하지 않은 상태라면 `UNKNOWN_TOPIC_OR_PARTITION` 오류로 NestJS 초기화가 실패하고 HTTP 서버가 열리지 않는다. `process.exit(1)` 처리로 컨테이너가 자동 재시작되며, 1~2회 재시작 후 토픽이 모두 생성되면 정상 기동된다. 수동으로 해결하려면 `docker compose restart cowork-chat`을 실행하면 된다.

`MINIO_PUBLIC_ENDPOINT`:
- `scripts/run/local/infra.sh`와 `scripts/run/local/*.sh`는 `.env`의 `__LOCAL_IP__`를 현재 LAN IP로 자동 치환한다.
- `docker compose up`를 직접 치면 이 치환이 적용되지 않으므로, 모바일 앱·외부 기기 업로드 테스트는 스크립트 경로를 사용해야 한다.
- 같은 머신에서만 테스트한다면 `http://localhost:9000`으로 충분하다.

Flyway 경고:
- MySQL 컨테이너가 `8.4.8`이라 Flyway 로그에 버전 불일치 경고가 뜬다.
- 현재는 실행에 지장 없다.

Spring Boot 초기 기동 시간:
- `cowork-config`가 healthy 상태가 될 때까지 최대 90초 대기가 설정돼 있다.
- 첫 `docker compose up` 시 Gradle 빌드가 포함되므로 이미지 생성에 수 분이 소요될 수 있다.

Docker Desktop 재시작 후 서비스 복구:
- Vault는 dev 모드(인메모리)로 동작해 **Docker Desktop 재시작 시 모든 시크릿이 초기화**된다.
- `vault-init`은 `restart: "no"`이므로 자동 재실행되지 않는다. 아래 순서로 수동 복구한다.

```bash
# 1. Vault 시크릿 재시드
docker compose up vault-init

# 2. Vault에서 시크릿을 읽는 서비스 재시작
docker compose restart cowork-gateway cowork-authorization cowork-notification cowork-voice cowork-chat
```

- `cowork-gateway`를 반드시 포함해야 한다. `jwt.secret`이 Vault → Config Server 경로로 주입되는데, Vault가 비어있는 상태로 기동하면 빈 시크릿으로 `signingKey`가 초기화되어 **모든 API 요청에서 401이 반환**된다. (`JwtProperties`에 `@RefreshScope`가 없어 자동 갱신 불가)

## 12. 남은 확인 항목

- `cowork-voice`: LiveKit 기본 키(`devkey` / `devsecret`)로 실제 WebRTC 세션 생성까지 앱 연동 관점에서 한 번 더 검증하면 된다.
