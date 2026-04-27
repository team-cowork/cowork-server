# 로컬 실행 가이드

이 문서는 `cowork-server`를 로컬에서 실제로 띄우기 위한 기준 문서다.

기준일:
- 2026-04-27

검증 기준:
- `docker-compose.yml`
- `scripts/run/local/*.sh`
- 각 서비스의 실제 설정 파일
- 로컬에서 직접 기동한 결과

## 1. 한눈에 보기

로컬 실행은 크게 2단계다.

1. Docker Compose로 공용 인프라를 먼저 올린다.
2. 각 서비스를 `scripts/run/local/*.sh` 또는 직접 `foreground`로 올린다.

현재 레포 기준으로 확인한 상태는 아래와 같다.

| 서비스 | 포트 | 실행 방식 | 2026-04-27 기준 상태 |
|---|---:|---|---|
| `cowork-config` | `8761` | Gradle `bootRun` | 기동 확인 |
| `cowork-gateway` | `8080` | Gradle `bootRun` | 기동 확인 |
| `cowork-authorization` | `8081` | Go `go run ./cmd` | 기동 확인 |
| `cowork-user` | `8082` | Gradle `bootRun` | 기동 확인 |
| `cowork-channel` | `8083` | Gradle `bootRun` | 기동 확인 |
| `cowork-team` | `8085` | Gradle `bootRun` | 기동 확인 |
| `cowork-notification` | `8086` | Go `go run ./cmd/server` | 기동 확인 |
| `cowork-preference` | `9001` | Gradle `run` | 기동 확인 |
| `cowork-chat` | `8087` | Nest `npm run start:dev` | 코드 오류로 기동 실패 |
| `cowork-voice` | `8084` | Go `go run ./cmd/server` | 환경변수 부족으로 기동 실패 |
| `cowork-project` | 미정 | 실행 스크립트 없음 | 로컬 실행 경로 미구성 |

## 2. 사전 준비

필수 런타임:
- Java 21
- Docker / Docker Compose
- Go
- Node.js / npm

권장:
- `screen`
  `scripts/run/local/_service.sh`가 백그라운드 실행 시 `screen`을 우선 사용한다.
  없으면 `nohup`으로 동작한다.

## 3. 환경변수

루트에서 `.env`가 필요하다.

```bash
cp .env.example .env
```

로컬 실행에 실제로 중요한 값:
- `SPRING_PROFILES_ACTIVE=local`
- `MYSQL_ROOT_PASSWORD`
- `MYSQL_USER`
- `MYSQL_PASSWORD`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `MONGO_ROOT_USERNAME`
- `MONGO_ROOT_PASSWORD`
- `JWT_SECRET`
- `MINIO_*`
- `GRAFANA_ADMIN_PASSWORD`

기능별 추가 필요 값:
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`
  `cowork-voice`와 `livekit` 컨테이너 모두에 필요하다.
- `DATAGSM_CLIENT_ID`
  `cowork-authorization`에서 필요하다.
- `docker/secrets/firebase-credentials.json`
  `cowork-notification`에서 필요하다.

주의:
- `cowork-config`를 직접 실행할 때 `.env`를 안 읽으면 `dev` 프로파일로 떠서 Git Config Server 설정을 요구하며 실패한다.
- 반드시 `SPRING_PROFILES_ACTIVE=local`이 적용된 상태로 실행해야 한다.

## 4. 인프라

`docker compose up -d`로 올라가는 항목:
- MySQL `3306`
- PostgreSQL `5432`
- MongoDB `27017`
- Kafka `9094`
- Kafka UI `8090`
- Vault `8200`
- MinIO API `9000`
- MinIO Console `9002`
- Redis `6379`
- LiveKit `7880`, `7881`
- Prometheus `9090`
- Grafana `3001`
- Loki `3100`

초기 DB 생성:
- MySQL: `docker/mysql/init.sh`
  `cowork_authorization`, `cowork_user`, `cowork_team`, `cowork_project`, `cowork_channel`, `cowork_notification` 생성
- PostgreSQL: `docker/postgres/init.sh`
  `cowork_preference` DB와 `preference` 스키마 생성

인프라 실행:

```bash
scripts/run/local/infra.sh start
```

상태 확인:

```bash
scripts/run/local/infra.sh status
```

로그 확인:

```bash
scripts/run/local/infra.sh logs
```

완전 초기화:

```bash
docker compose down -v
docker compose up -d
```

## 5. 권장 기동 순서

1. `cowork-config`
2. `cowork-gateway`
3. `cowork-preference`
4. `cowork-authorization`
5. `cowork-user`
6. `cowork-team`
7. `cowork-channel`
8. `cowork-notification`
9. `cowork-chat`
10. `cowork-voice`

이유:
- `cowork-config`가 Config Server + Eureka Server 역할을 한다.
- `gateway`, `user`, `team`, `channel`, `preference`, `notification`은 Config Server 또는 Eureka 의존성이 있다.
- `notification`은 `preference`, `user`, `team` URL을 사용한다.
- `voice`는 LiveKit, MongoDB, Kafka, Channel API를 요구한다.

## 6. 실행 명령

기본:

```bash
scripts/run/local/config.sh start
scripts/run/local/gateway.sh start
scripts/run/local/preference.sh start
scripts/run/local/authorization.sh start
scripts/run/local/user.sh start
scripts/run/local/team.sh start
scripts/run/local/channel.sh start
scripts/run/local/notification.sh start
scripts/run/local/chat.sh start
scripts/run/local/voice.sh start
```

문제 추적 시 권장:

```bash
scripts/run/local/config.sh foreground
scripts/run/local/gateway.sh foreground
scripts/run/local/user.sh foreground
```

공통 제어:

```bash
scripts/run/local/<service>.sh status
scripts/run/local/<service>.sh logs
scripts/run/local/<service>.sh stop
scripts/run/local/<service>.sh restart
```

예시:

```bash
scripts/run/local/user.sh logs
scripts/run/local/team.sh status
scripts/run/local/channel.sh stop
```

## 7. 실제 검증 결과

아래는 2026-04-27 기준 실제로 확인한 내용이다.

정상 기동 확인:
- `cowork-config`
  `local`, `native` 프로파일로 기동 확인
  Kafka `springCloudBus` 연결 확인
- `cowork-gateway`
  Config Server에서 `cowork-gateway-local.yml` 로드 확인
  Eureka 등록 확인
- `cowork-authorization`
  MySQL 연결 후 `:8081` 리슨 확인
  Eureka 등록 확인
- `cowork-user`
  MySQL 연결 확인
  Flyway `V1~V3` 검증 확인
  Eureka 등록 확인
  Kafka consumer 연결 확인
- `cowork-team`
  MySQL 연결 확인
  Flyway 적용 확인
  Eureka 등록 확인
- `cowork-channel`
  MySQL 연결 확인
  Flyway 적용 확인
  Eureka 등록 확인
- `cowork-preference`
  Config Server 로드 확인
  PostgreSQL Flyway `V1~V4` 검증 확인
  Redis/Kafka 초기화 확인
  `9001` 리슨 확인
- `cowork-notification`
  Config Server 로드 확인
  Kafka consumer 시작 로그 확인

실패 확인:
- `cowork-chat`
  Nest DI 오류로 실패
  `MembershipConsumer`가 `ConfigService`를 주입받는데 `MembershipModule`에 `ConfigModule` import가 없다.
  현재 오류 메시지:
  `Nest can't resolve dependencies of the MembershipConsumer ... ConfigService`
- `cowork-voice`
  `LIVEKIT_API_KEY` 누락으로 즉시 실패
  현재 `.env`에 `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`가 비어 있으면 실행 불가
- `cowork-project`
  `settings.gradle.kts`에 include되지 않았고 `scripts/run/local/project.sh`도 없다.
  현재 로컬 실행 루트가 없다.

## 8. 알려진 주의사항

`cowork-config`:
- `.env` 없이 직접 `./gradlew :cowork-config:bootRun` 하면 `dev` 프로파일로 올라가면서 Git URI가 없어서 실패한다.

`cowork-chat`:
- 현재는 환경 문제가 아니라 코드 문제다.
- 실행 가이드만 따라도 뜨지 않는다.

`cowork-voice`:
- LiveKit 컨테이너만 올려도 충분하지 않다.
- 애플리케이션과 컨테이너에 같은 `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`가 채워져야 한다.

`cowork-notification`:
- `docker/secrets/firebase-credentials.json`이 실제로 있어야 한다.
- 없으면 `FCM_CREDENTIALS_FILE`을 명시적으로 바꿔야 한다.

백그라운드 스크립트:
- `.run/*.pid` 파일이 남아 있을 수 있다.
- 서비스가 실제로 꺼져 있는데 pid 파일만 남은 경우 `scripts/run/local/<service>.sh stop`으로 정리하는 편이 안전하다.

임시 Gradle 캐시:
- 레포 아래에 `.gradle-local/`를 만들면서 실행하는 방식은 권장하지 않는다.
- 이 디렉터리는 코드가 아니라 Gradle 사용자 홈 대체용 캐시이며, 크기가 빠르게 커질 수 있다.
- 불가피하게 써야 해도 검증 후 바로 정리하고, 기본 로컬 가이드의 표준 경로로 남기지 않는다.

Flyway 경고:
- MySQL 컨테이너 버전은 `8.4.8`
- 현재 Flyway 로그에는 `MySQL 8.4 is newer than this version of Flyway` 경고가 뜬다.
- 현재는 실행되지만 장기적으로 버전 정합성을 맞추는 편이 낫다.

## 9. 자주 쓰는 확인 포인트

Eureka:
- `http://localhost:8761`

Gateway Swagger:
- `http://localhost:8080/swagger-ui.html`

Kafka UI:
- `http://localhost:8090`

Prometheus:
- `http://localhost:9090`

Grafana:
- `http://localhost:3001`

MinIO Console:
- `http://localhost:9002`

## 10. 추천 실행 시나리오

최소 API 개발 세트:

```bash
scripts/run/local/infra.sh start
scripts/run/local/config.sh start
scripts/run/local/gateway.sh start
scripts/run/local/authorization.sh start
scripts/run/local/user.sh start
scripts/run/local/team.sh start
scripts/run/local/channel.sh start
scripts/run/local/preference.sh start
scripts/run/local/notification.sh start
```

채팅까지 보고 싶을 때:
- 위 세트 + `cowork-chat`
- 단, 현재 코드 수정 없이 기동 불가

음성까지 보고 싶을 때:
- 위 세트 + `cowork-voice`
- 단, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`를 먼저 채워야 함

## 11. 다음 정리 대상

문서 기준으로 추가 정리가 필요한 부분:
- `cowork-chat` DI 오류 수정
- `cowork-voice` 로컬용 기본 키/샘플 값 가이드 추가
- `cowork-project` 실행 경로 정리
- 각 서비스 헬스체크 URL과 대표 테스트 API를 문서에 추가
