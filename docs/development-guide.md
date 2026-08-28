# 개발 가이드

cowork-server MSA 모노레포 개발 지침서입니다.

---

## 목차

1. [프로젝트 구조](#1-프로젝트-구조)
2. [새 서비스 추가](#2-새-서비스-추가)
3. [DB 스키마 관리](#3-db-스키마-관리)
4. [서비스 간 통신](#4-서비스-간-통신)
5. [인증 / 인가](#5-인증--인가)
6. [환경 변수 및 설정 관리](#6-환경-변수-및-설정-관리)
7. [로컬 개발 환경 실행 순서](#7-로컬-개발-환경-실행-순서)
8. [Swagger / 모니터링](#8-swagger--모니터링)

---

## 1. 프로젝트 구조

단일 레포지토리(모노레포) 안에 모든 서비스가 **Flat 구조**로 위치합니다.

```
cowork-server/
├── cowork-gateway/       Spring Cloud Gateway (JWT 검증, 라우팅) — Kotlin
├── cowork-config/        Spring Cloud Config Server + Eureka Server — Kotlin
├── cowork-authorization/ 인증 서비스 (JWT 발급, DataGSM OAuth2) — Go
├── cowork-user/          사용자 프로필 관리 — Elixir
├── cowork-team/          팀 관리 — Kotlin (Spring Boot)
├── cowork-project/       프로젝트 관리 — Kotlin (Spring Boot)
├── cowork-roadmap/       전공/포지션별 온보딩 로드맵 — Java (Spring Boot WebFlux + R2DBC)
├── cowork-channel/       채널 관리 (텍스트/음성/웹훅 등) — Kotlin (Spring Boot)
├── cowork-preference/    사용자·팀·채널·저장소 설정 및 사용자 정의 팀 역할 관리 — Kotlin (Vert.x)
├── cowork-chat/          채팅 메시지 (MongoDB + Elasticsearch) — NestJS (TypeScript)
├── cowork-voice/         음성 채널 (MongoDB + Redis) — Go
├── cowork-notification/  알림 (FCM 푸시 + SSE) — Go
├── cowork-promotion/     서비스 소개 페이지 — TypeScript 정적 사이트 (프레임워크 없음)
└── cowork-monitoring/    Prometheus/Grafana 설정 (앱 없음)
```

### 모듈 네이밍 규칙

- 모든 서비스 디렉터리명은 `cowork-` 접두사로 시작합니다.
- JVM 서비스는 `settings.gradle.kts`에 등록합니다. 현재 등록된 모듈은 `cowork-gateway`, `cowork-config`,
  `cowork-channel`, `cowork-project`, `cowork-team`, `cowork-preference`, `cowork-roadmap` 7개입니다.
- 등록되어 있어도 빌드 소유권은 다를 수 있습니다. `cowork-project`는 Maven(`pom.xml`), `cowork-preference`는
  Amper(`module.yaml`)가 source of truth이고 `build.gradle.kts`는 위임만 합니다.
- JVM 외 서비스(NestJS, Go, Elixir, 정적 사이트)는 Gradle에 포함하지 않습니다.
- 새 모듈을 만들면 `scripts/bump.sh`(`make bump`)에도 추가해 릴리스 버전이 스탬프되게 합니다.

---

## 2. 새 서비스 추가

### JVM (Spring Boot / Vert.x) 서비스

1. 루트에 `cowork-{name}/` 디렉터리 생성
2. `settings.gradle.kts`에 `include("cowork-{name}")` 추가
3. 빌드 도구의 단일 진실 공급원을 정하고 기존 모듈을 참고해 설정
   - Gradle: config, gateway, channel, team, roadmap
   - Maven: project (`pom.xml`, Gradle 파일은 위임 wrapper)
   - Kotlin Toolchain(Amper): preference (`module.yaml`, Gradle 파일은 위임 wrapper)
4. `cowork-{name}/README.md` 작성 (스택, 역할, 포트, DB 명시)
5. `.gitignore` 추가 (Gradle 기반 템플릿 사용)
6. MySQL 사용 시 [DB 스키마 관리](#3-db-스키마-관리) 절차 따르기
7. Eureka Client 등록 (`spring.application.name: cowork-{name}`)

### JVM 외 서비스 (NestJS / Go / Elixir / Nuxt.js 등)

1. 루트에 `cowork-{name}/` 디렉터리 생성
2. `cowork-{name}/README.md` 작성
3. 언어에 맞는 `.gitignore` 추가
4. Gradle에는 **포함하지 않음**

---

## 3. DB 스키마 관리

### Spring Boot 서비스 (Flyway)

MySQL을 사용하는 Spring Boot 서비스(channel, team, project, roadmap)는 **Flyway**로 스키마를 관리합니다. JPA 서비스는 `ddl-auto: none`을 사용하고, roadmap은 R2DBC 쿼리와 JDBC Flyway를 함께 사용합니다.

**파일 위치**

```
cowork-{name}/
└── src/main/resources/db/migration/
    ├── V1__init.sql
    ├── V2__add_column.sql
    └── V3__create_index.sql
```

**application.yml 설정**

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### Go 서비스 (자체 마이그레이션 러너)

Go 서비스(`cowork-authorization`, `cowork-notification`)는 자체 마이그레이션 러너(`internal/infra/mysql/migrate.go`)를 사용합니다.<br>
SQL 파일 위치와 네이밍 규칙은 Spring Boot와 동일합니다.

```
cowork-{name}/
└── src/main/resources/db/migration/
    ├── V1__init.sql
    └── V2__add_column.sql
```

### Elixir 서비스 (cowork-user)

`cowork-user`는 동일한 `src/main/resources/db/migration/` SQL을 사용합니다. 컨테이너 시작 시 `docker-entrypoint.sh`가 Flyway CLI로 migration을 적용한 뒤 Mix release를 실행합니다.

### 파일 네이밍 규칙

```
V{버전}__{설명}.sql
```

- 버전은 정수 단위로 순차 증가합니다. (`V1`, `V2`, `V3`, ...)
- 설명은 스네이크 케이스로 작성합니다. (`add_github_id`, `create_index_role`)
- **한 번 커밋된 마이그레이션 파일은 절대 수정하지 않습니다.**<br>
  내용을 바꿔야 하면 새 버전 파일을 추가합니다.

### 테이블 네이밍 규칙

- 모든 테이블명은 `tb_` 접두사로 시작합니다.
- 인덱스: `idx_tb_{테이블}_{컬럼}`
- 유니크 키: `uq_tb_{테이블}_{컬럼}`
- 외래 키: `fk_tb_{테이블}_{참조대상}`

### 서비스 간 참조

MSA 원칙에 따라 **서비스 간 실제 FK 제약을 걸지 않습니다.**<br>
다른 서비스의 ID를 참조할 때는 컬럼 `COMMENT`에 출처를 명시합니다.

```sql
team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
```

### MongoDB 서비스 (cowork-chat, cowork-voice)

Flyway를 사용하지 않습니다. `cowork-chat`은 Mongoose schema와 `schema/message.schema.md`를 함께 관리합니다. `cowork-voice`의 컬렉션 구조는 Go 모델·repository가 현재 구현 기준이며, 구조 변경 시 별도 `schema/` 문서를 추가해 저장 형식을 명시합니다.

### PostgreSQL 서비스 (cowork-preference)

Vert.x + Flyway를 사용합니다. 스키마는 `src/main/resources/db/migration/` 경로의 SQL 파일로 관리하며, 애플리케이션 기동 시 자동으로 마이그레이션이 실행됩니다.

---

## 4. 서비스 간 통신

### 동기 통신 (REST)

- 클라이언트 → Gateway → 각 서비스 경로로만 호출합니다.
- Gateway는 Eureka의 `lb://cowork-{name}` 대상으로 라우팅합니다.
- 다른 서비스의 durable state는 authoritative owner가 transactional outbox로 발행하고,
  호출자가 idempotent 로컬 projection으로 소비합니다. 내부 REST/Feign으로 상태를 조회하지 않습니다.
- 즉시 응답, 생성 ID, 검증 오류만으로는 내부 HTTP 예외가 될 수 없습니다. durable state로
  재구성할 수 없는 request-scoped 작업만 이유를 문서화하고 허용합니다.
- 명시적으로 보류된 GitHub App API와 외부 provider API는 이 내부 상태 조회 규칙의 대상이 아닙니다.

### 비동기 통신 (Kafka)

서비스 간 상태 전파와 조회 모델 동기화에는 Kafka를 사용합니다. 상태 토픽은 aggregate ID 기반 key,
UTC `occurredAt`, 삭제 tombstone, 주기 snapshot을 계약으로 사용합니다.

Projection consumer는 broker group offset을 복구 기준으로 사용하지 않습니다. 로컬 projection 저장소에
`(consumer group, topic, partition, next_offset)` checkpoint를 상태 반영과 함께 기록합니다. Client가
broker topic UUID를 제공하면 checkpoint와 함께 저장해 assignment와 range 검사 때 동일성을 검증합니다.
UUID를 제공하지 못하는 client는 프로세스 재시작·강제 복구마다 durable replay generation을 만들고,
assignment 시 checkpoint와 snapshot barrier를 broker earliest로 원자적으로 초기화하며 이전 replica의
lease를 fencing합니다. 숫자 checkpoint만으로 이전 topic의 연속성을 추정하지 않습니다.

기동 시점에 관련된 모든 topic partition의 end offset을 고정한 뒤 shared checkpoint가 전부 도달할 때만
readiness와 Eureka 트래픽을 엽니다. 따라서 DB 재구축, consumer group offset 선행, 다중 replica 환경에서도
projection이 비거나 부분적인 상태를 정상 응답으로 노출하지 않습니다. 준비 완료 뒤에도 현재 broker
high-watermark와 checkpoint를 계속 비교하며, 필수 projection이 뒤처지면 readiness를 다시 닫습니다.
동기화 중 projection 의존 API는 `403`이나 빈 결과 대신 `503`을 반환합니다.

action-only 계약 위반 레코드는 격리 성공 후 checkpoint를 진전시킬 수 있습니다. snapshot-backed state
레코드가 잘못되면 격리와 함께 durable invalid-record latch를 남기고 readiness를 즉시 닫습니다. 같은
aggregate의 startup·주기 snapshot을 replica 전체에서 하나의 분산 락으로 직렬화하고, 서로 다른
`snapshotId`의 full snapshot 완료로 gap 이후 시작한 재구성이 증명될 때만 latch를 해제합니다. 중복
marker는 새 복구 run으로 세지 않습니다. DB·MongoDB·Kafka 같은 일시적 인프라 오류에서는 checkpoint와
consumer position을 절대 진전시키지 않습니다. Docker/Eureka는 liveness가 아니라 각 서비스의
readiness endpoint를 트래픽 허용 기준으로 사용합니다.

Kafka 토픽 이름은 배포 후 불변입니다. UUID를 읽을 수 있는 client는 같은 이름의 topic 교체를 즉시
감지합니다. topic identity를 제공하지 않는 client는 겹치는 offset 범위로 같은 이름이 교체되면 다음
fenced replay 경계 전까지 연속성을 증명할 수 없으므로, topic 불변성과 coordinated projection rebuild가
필수입니다. 특히 authoritative owner DB나 Kafka dataset 교체로 과거 tombstone까지 사라지면 full
replay만으로 projection의 잔존 행을 판별할 수 없습니다. 이 경우 관련 projection table, snapshot barrier,
checkpoint를 함께 재구축한 뒤에만 트래픽을 다시 엽니다.

주기 snapshot의 `occurredAt`은 발행 시각이 아니라 source row의 실제 변경 시각을 재사용합니다.
발행 시각을 새 버전으로 쓰면 삭제와 동시에 실행된 snapshot UPSERT가 tombstone보다 최신이 되어
삭제 상태를 되살릴 수 있습니다. DB 기반 상태 변경과 Kafka 상태 이벤트는 같은 transaction에
outbox로 적재하고, relay는 Kafka ack 이후에만 outbox 행을 제거합니다. relay 재시작에 따른 중복은
consumer의 version/LWW 규칙으로 흡수하며 실패한 tombstone은 최대 재시도 횟수로 폐기하지 않습니다.

full snapshot의 마지막에는 각 partition으로 `PROJECTION_SNAPSHOT_COMPLETED` marker를 명시적으로
보냅니다. Consumer는 시작 시 캡처한 high-watermark뿐 아니라 모든 partition의 marker를 확인해야
ready가 됩니다. 따라서 새로 생성된 빈 state topic을 snapshot 완료로 간주하지 않습니다. 반대로
재구성 가능한 source snapshot이 없는 action stream은 이 barrier 대상에 넣거나 빈 marker로 위장하지
않습니다. PostgreSQL outbox는 sequence 값이 commit 순서를 보장하지 않으므로 producer transaction을
transaction-scoped advisory lock으로 직렬화합니다. Relay의 `FOR UPDATE`만으로는 아직 commit되지 않은
낮은 sequence 행을 볼 수 없습니다.

| 토픽                                          | Producer                       | Consumer                                                                 | 용도                                                       |
|-----------------------------------------------|--------------------------------|--------------------------------------------------------------------------|------------------------------------------------------------|
| `user.data.sync`                              | cowork-authorization           | cowork-user                                                              | DataGSM webhook의 계정·프로필 변경 요청                    |
| `user.identity.command`                       | cowork-authorization           | cowork-user                                                              | 로그인 시 계정·프로필 생성 또는 동기화 command             |
| `user.identity.command-result`                | cowork-user                    | cowork-authorization                                                     | identity command의 owner commit 결과                       |
| `team.lifecycle`                              | cowork-team                    | cowork-channel, cowork-project, cowork-notification                      | team key별 최신 생명주기 상태·삭제와 연쇄 정리             |
| `team.member.event`                           | cowork-team                    | cowork-channel, cowork-project, cowork-user, cowork-roadmap, cowork-chat | 버전 기반 팀 멤버십 projection                             |
| `user.profile.event`                          | cowork-user                    | cowork-project, cowork-chat, cowork-notification                         | 사용자 표시·GitHub identity 정보 projection                |
| `user.presence.event`                         | cowork-authorization           | cowork-user                                                              | 사용자 접속 상태 projection                                |
| `channel.event`                               | cowork-channel                 | cowork-project, cowork-chat                                              | 채널 메타데이터와 GitHub webhook 대상 정합성 projection    |
| `channel.member.event`                        | cowork-channel                 | cowork-chat, cowork-voice                                                | 채널 멤버십 projection                                     |
| `project.event`                               | cowork-project                 | cowork-channel, cowork-chat                                              | 프로젝트 메타데이터 projection                             |
| `project.member.event`                        | cowork-project                 | cowork-chat                                                              | 프로젝트 멤버십 projection                                 |
| `project.github-repo.event`                   | cowork-project                 | cowork-chat                                                              | 프로젝트별 GitHub 저장소 연결·webhook 대상 상태 projection |
| `preference.channel-notification.changed`     | cowork-preference              | cowork-notification                                                      | 채널 알림 설정 projection                                  |
| `preference.team-role.command`                | cowork-team                    | cowork-preference                                                        | 사용자 정의 팀 역할·할당 비동기 command                    |
| `preference.team-role.changed`                | cowork-preference              | cowork-team                                                              | 사용자 정의 팀 역할·할당 상태 projection                   |
| `preference.team-role.command-result`         | cowork-preference              | cowork-team                                                              | 팀 역할 command 처리 결과                                  |
| `preference.github-repo.setting.command`      | cowork-project                 | cowork-preference                                                        | GitHub 저장소 설정 비동기 command                          |
| `preference.github-repo.setting.state`        | cowork-preference              | cowork-project                                                           | GitHub 저장소 설정 상태 projection                         |
| `preference.github-repo.setting.result`       | cowork-preference              | cowork-project                                                           | GitHub 저장소 설정 command 처리 결과                       |
| `chat.message`                                | cowork-chat                    | cowork-chat                                                              | 메시지 비동기 저장·브로드캐스트                            |
| `notification.trigger`                        | cowork-team, cowork-chat       | cowork-notification                                                      | FCM·SSE 알림 발송                                          |
| `github.issue.create` / `github.issue.result` | cowork-chat / 외부 GitHub 연동 | 외부 GitHub 연동 / cowork-chat                                           | GitHub 이슈 slash command                                  |
| `github.repo.event`                           | 외부 GitHub App 연동           | cowork-chat                                                              | GitHub 저장소 action stream                                |
| `voice.event`                                 | cowork-voice                   | 연동 서비스                                                              | 음성 세션 이벤트                                           |
| `preference.status.changed`                   | cowork-preference              | 연동 서비스                                                              | 사용자 상태 변경                                           |
| `preference.team.setting.changed`             | cowork-preference              | 연동 서비스                                                              | 팀 설정 변경                                               |

토픽 이름은 `{도메인}.{이벤트}` 형식을 따릅니다.
계정과 프로필 identity는 `cowork-user`가 소유합니다. authorization은 DataGSM 인증 정보로
`user.identity.command`를 발행하고 user의 commit 결과를 확인한 뒤에만 세션과 토큰을 발급합니다.
DataGSM webhook 변경은 `user.data.sync`로 전달하며, 공개 프로필의 `name`과 `github_id` 변경도
user의 공개 API와 저장소에서 처리합니다.
팀의 built-in 멤버십 역할은 `cowork-team`이 소유하고, 사용자 정의 역할과 할당은
`cowork-preference`가 소유합니다. team의 공개 API 위치는 소유권을 옮기지 않으며,
command/result와 local state projection으로 비동기 처리합니다. GitHub 저장소의 `label_auto_apply`도
`cowork-preference`가 소유하고 project는 local projection을 읽습니다.

---

## 5. 인증 / 인가

### 흐름

```
클라이언트 → Gateway (JWT 검증) → 하위 서비스 (헤더로 사용자 정보 수신)
```

### Gateway가 하위 서비스로 전달하는 헤더

| 헤더          | 값       | 설명                        |
|---------------|----------|-----------------------------|
| `X-User-Id`   | `Long`   | 사용자 ID                   |
| `X-User-Role` | `String` | 사용자 권한 (ADMIN, MEMBER) |

### 하위 서비스 처리 원칙

- 하위 서비스는 JWT를 직접 파싱하지 않습니다.
- `X-User-Id`, `X-User-Role` 헤더를 신뢰하고 사용합니다.
- Gateway를 우회한 직접 호출을 운영 환경에서는 차단합니다.

---

## 6. 환경 변수 및 설정 관리

전체 공급 경로와 서비스별 부트스트랩 기준은 [`docs/configuration.md`](configuration.md)를 따릅니다.

### 원칙

| 종류                                | 관리 방법                                                                |
|-------------------------------------|--------------------------------------------------------------------------|
| DB 접속 정보, JWT 시크릿 등 민감 값 | HashiCorp Vault, 배포 시 CI/CD secret으로 주입                           |
| 서비스별 일반 설정                  | `cowork-config` Config Server (`local`/`prod` 모두 classpath `configs/`) |
| 저장소 공통 로컬 설정               | `cowork-config/src/main/resources/configs/*-local.yml`                   |
| 개발자 머신별 값                    | 루트 `.env` 또는 gitignored 로컬 override                                |

### Vault (로컬 개발)

로컬 환경에서는 HashiCorp Vault Dev 모드로 시크릿을 관리합니다.<br>
`docker compose up -d` 시 `vault-init` 컨테이너가 자동으로 시크릿을 주입합니다.

- Vault UI: `http://localhost:8200` (토큰: `dev-root-token`)
- 시크릿 경로: `secret/application` (공통), `secret/cowork-{name}` (서비스별)
  - 자동 주입 경로: gateway, authorization, notification, preference, user, project, voice, chat, channel
  - team·roadmap은 `secret/application`의 공통 DB/SeaweedFS 값을 사용합니다.

### application.yml 작성 원칙

민감한 값은 Vault에서 주입하고 Config 파일에는 placeholder 또는 property 이름만 둡니다.

```yaml
# 올바른 예
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

# 잘못된 예 (절대 커밋 금지)
spring:
  datasource:
    password: mypassword123
```

### Spring Config Client (비-Spring 서비스)

Go, Elixir, NestJS, Vert.x 서비스도 `cowork-config`에서 설정을 받아옵니다. 구현 위치는 런타임마다 다릅니다.

- Go: `internal/config/`
- Elixir user: `CoworkUser.AppConfig`
- NestJS chat: 시작 단계의 Config Server loader
- Vert.x preference: `AppConfig`

### Flyway + JPA 설정 (JPA 서비스 예시)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

## 7. 로컬 개발 환경 실행 순서

### 1단계 — 환경 변수 파일 준비

```bash
cp .env.example .env
# .env 파일에 필요한 값 입력
```

### 2단계 — 인프라 기동 (Docker Compose)

루트의 `docker-compose.yml`과 자동 병합되는 `docker-compose.override.yml`로 인프라와 애플리케이션을 함께 띄웁니다.

```bash
# 전체 기동
docker compose up -d

# 특정 서비스만 기동
docker compose up -d mysql mongodb kafka
```

**인프라 포트 정보**

| 서비스            | 호스트 포트 | 비고                                      |
|-------------------|-------------|-------------------------------------------|
| MySQL             | 3306        | 서비스별 DB 자동 생성 (7개)               |
| PostgreSQL        | 5432        | cowork-preference 전용                    |
| MongoDB           | 27017       | cowork-chat, cowork-voice                 |
| Kafka             | 9094        | 호스트 접근용 (컨테이너 간: 9092)         |
| Kafka UI          | 8090        | 브라우저에서 토픽/메시지 확인             |
| Vault             | 8200        | 시크릿 관리 (토큰: `dev-root-token`)      |
| SeaweedFS         | 9000        | S3 호환 오브젝트 스토리지                 |
| SeaweedFS Console | 9002        | 브라우저 UI                               |
| Elasticsearch     | 9200        | 채팅 메시지 검색 (cowork-chat)            |
| Redis             | 6379        | Gateway rate limit, chat·voice·preference |
| LiveKit           | 7880        | 음성 서버                                 |
| Prometheus        | 9090        | 메트릭 수집                               |
| Grafana           | 3001        | 모니터링 대시보드                         |
| Loki              | 3100        | 로그 수집                                 |

MySQL 최초 기동 시 `docker/mysql/init.sh`가 자동 실행되어 서비스별 스키마를 생성합니다
(`cowork_authorization`, `cowork_user`, `cowork_team`, `cowork_project`, `cowork_channel`, `cowork_notification`, `cowork_roadmap`).<br>
볼륨이 이미 존재하면 init 스크립트는 재실행되지 않습니다. 초기화가 필요하면 볼륨을 삭제하세요.

```bash
# 볼륨까지 삭제 후 재생성 (데이터 초기화)
docker compose down -v
docker compose up -d
```

**유용한 명령어**

```bash
docker compose logs -f          # 전체 로그
docker compose logs -f mysql    # 특정 서비스 로그
docker compose ps               # 컨테이너 상태
docker compose down             # 중지 (데이터 유지)
docker compose down -v          # 중지 + 데이터 초기화
```

> 로컬 DB·Kafka가 비어 있는 상태에서 처음 전체를 띄우는 절차, projection snapshot marker 확인,
> 기동 실패 진단은 [`docs/local-run-guide.md`](./local-run-guide.md)를 따릅니다. 개별 서비스를
> 호스트에서 직접 실행할 때는 `scripts/run/local/{service}.sh`를 사용합니다.

### 3단계 — 애플리케이션 서비스 기동 순서

MSA 서비스 간 의존성이 있으므로 아래 순서로 기동합니다.

```
1. cowork-config   (Eureka + Config Server — 가장 먼저 기동)
2. cowork-gateway  (Config Server에 등록 후 기동)
3. authorization
4. user 및 나머지 비즈니스 서비스  (team, project, roadmap, channel, preference, notification, chat, voice)
```

Compose 실행 시 세부 의존 순서는 `depends_on`이 처리합니다. user는 authorization의 presence snapshot source가 기동한 뒤 시작합니다. 직접 실행할 때는 voice가 Kafka의 channel membership projection과 LiveKit·Redis·MongoDB에, notification이 Kafka의 user/team/preference projection과 MySQL에 의존한다는 점을 함께 확인합니다.

**서비스 포트 정보**

| 서비스               | 포트 | 스택                                    |
|----------------------|------|-----------------------------------------|
| cowork-config        | 8761 | Kotlin (Spring Cloud Config + Eureka)   |
| cowork-gateway       | 8080 | Kotlin (Spring Cloud Gateway)           |
| cowork-authorization | 8081 | Go                                      |
| cowork-user          | 8082 | Elixir                                  |
| cowork-channel       | 8083 | Kotlin (Spring Boot)                    |
| cowork-voice         | 8089 | Go                                      |
| cowork-team          | 8085 | Kotlin (Spring Boot)                    |
| cowork-notification  | 8086 | Go                                      |
| cowork-chat          | 8087 | NestJS (TypeScript)                     |
| cowork-project       | 8084 | Kotlin (Spring Boot, Maven)             |
| cowork-roadmap       | 8088 | Java (Spring Boot WebFlux + R2DBC)      |
| cowork-preference    | 9001 | Kotlin (Vert.x, Kotlin Toolchain/Amper) |

### Makefile 명령어

```bash
make version   # VERSION 파일 내용 출력
make bump      # scripts/bump.sh로 모든 빌드 파일에 버전 스탬프
make setup     # Go 서비스(authorization·notification·voice) swagger 생성 및 의존성 설치
make init-logs # scripts/init-log-dirs.sh로 로그 디렉터리 초기화
make tag       # 버전 스탬프된 빌드 파일을 커밋하고 v{VERSION} 태그 생성
make release   # make tag 후 origin main으로 태그까지 push
```

`make release`는 `main`에 직접 push하므로 릴리스 시점에만 사용합니다.

---

## 8. Swagger / 모니터링

### Swagger (Gateway 경유)

Gateway는 서비스별 OpenAPI 문서를 `/v3/api-docs/{service}`로 프록시합니다. 로컬 통합 Swagger UI는 `http://localhost:8080/swagger-ui.html`에서 확인합니다. 프로파일별 지원 서비스와 라우트는 `cowork-config/src/main/resources/configs/cowork-gateway-{local,prod}.yml`를 기준으로 합니다.

### Prometheus / Grafana

`docker-compose.yml`에서 Prometheus/Grafana가 함께 기동되며, Prometheus는 `cowork-monitoring/prometheus/prometheus.yml`에 정의된 타겟을 스크랩합니다.

- Grafana: `http://localhost:3001`
- Prometheus: `http://localhost:9090`

Loki 파일 로그 수집은 아직 모든 서비스에 적용되지 않았습니다. 실제 수집 범위와 남은 작업은 `docs/grafana-logging-spec.md`를 참고합니다.
