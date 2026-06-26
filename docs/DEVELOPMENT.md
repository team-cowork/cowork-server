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
7. [코딩 컨벤션](#7-코딩-컨벤션)
8. [Git 브랜치 전략](#8-git-브랜치-전략)
9. [로컬 개발 환경 실행 순서](#9-로컬-개발-환경-실행-순서)

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
├── cowork-preference/    팀 설정 관리 — Kotlin (Vert.x)
├── cowork-chat/          채팅 메시지 (MongoDB + Elasticsearch) — NestJS (TypeScript)
├── cowork-voice/         음성 채널 (MongoDB + Redis) — Go
├── cowork-notification/  알림 (FCM 푸시 + SSE) — Go
├── cowork-promotion/     서비스 소개 페이지 — Nuxt.js (Vue)
└── cowork-monitoring/    Prometheus/Grafana 설정 (앱 없음)
```

### 모듈 네이밍 규칙

- 모든 서비스 디렉터리명은 `cowork-` 접두사로 시작합니다.
- Spring Boot / Vert.x 기반 JVM 서비스는 `settings.gradle.kts`에 등록합니다.
- JVM 외 서비스(NestJS, Go, Elixir, Nuxt.js 등)는 Gradle에 포함하지 않습니다.

---

## 2. 새 서비스 추가

### JVM (Spring Boot / Vert.x) 서비스

1. 루트에 `cowork-{name}/` 디렉터리 생성
2. `settings.gradle.kts`에 `include("cowork-{name}")` 추가
3. `cowork-{name}/build.gradle.kts` 작성 (기존 모듈 참고)
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

MySQL을 사용하는 Spring Boot 서비스는 **Flyway**로 스키마를 관리합니다.<br>
`ddl-auto`는 반드시 `none`으로 설정합니다.

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

Flyway를 사용하지 않습니다. 스키마 정의는 각 서비스의 `schema/` 디렉터리에 문서로 관리합니다.

### PostgreSQL 서비스 (cowork-preference)

Vert.x + Flyway를 사용합니다. 스키마는 `src/main/resources/db/migration/` 경로의 SQL 파일로 관리하며, 애플리케이션 기동 시 자동으로 마이그레이션이 실행됩니다.

---

## 4. 서비스 간 통신

### 동기 통신 (REST)

- 클라이언트 → Gateway → 각 서비스 경로로만 호출합니다.
- 서비스 간 내부 호출은 **OpenFeign** 또는 **WebClient**를 사용합니다.
- Gateway의 Eureka 로드밸런싱을 통해 라우팅합니다. (`lb://cowork-{name}`)

### 비동기 통신 (Kafka)

이벤트 기반 통신이 필요한 경우 Kafka를 사용합니다.

| 토픽 | Producer | Consumer | 용도 |
|---|---|---|---|
| `chat.message` | cowork-chat | cowork-chat | 메시지 저장 |
| `channel.member.event` | cowork-channel | cowork-chat | 채널 멤버십 동기화 |
| `notification.trigger` | 모든 서비스 | cowork-notification | 알림 발송 (FCM + SSE) |
| `team.lifecycle` | cowork-team | cowork-channel, cowork-project | 팀 생성/삭제 동기화 |
| `user.lifecycle` | cowork-user | cowork-channel, cowork-project | 유저 데이터 동기화 |
| `preference.team.setting.changed` | cowork-preference | 관련 서비스 | 팀 설정 변경 |

토픽 이름은 `{도메인}.{이벤트}` 형식을 따릅니다.

---

## 5. 인증 / 인가

### 흐름

```
클라이언트 → Gateway (JWT 검증) → 하위 서비스 (헤더로 사용자 정보 수신)
```

### Gateway가 하위 서비스로 전달하는 헤더

| 헤더 | 값 | 설명 |
|---|---|---|
| `X-User-Id` | `Long` | 사용자 ID |
| `X-User-Role` | `String` | 사용자 권한 (ADMIN, MEMBER) |

### 하위 서비스 처리 원칙

- 하위 서비스는 JWT를 직접 파싱하지 않습니다.
- `X-User-Id`, `X-User-Role` 헤더를 신뢰하고 사용합니다.
- Gateway를 우회한 직접 호출을 운영 환경에서는 차단합니다.

---

## 6. 환경 변수 및 설정 관리

### 원칙

| 종류 | 관리 방법 |
|---|---|
| DB 접속 정보, JWT 시크릿 등 민감 값 | HashiCorp Vault (로컬) / GitHub Secrets (CI/CD) |
| 서비스별 일반 설정 | `cowork-config` Config Server |
| 로컬 전용 설정 | `application-local.yml` (`.gitignore` 처리됨) |

### Vault (로컬 개발)

로컬 환경에서는 HashiCorp Vault Dev 모드로 시크릿을 관리합니다.<br>
`docker compose up -d` 시 `vault-init` 컨테이너가 자동으로 시크릿을 주입합니다.

- Vault UI: `http://localhost:8200` (토큰: `dev-root-token`)
- 시크릿 경로: `secret/application` (공통), `secret/cowork-{name}` (서비스별)
  - 현재 vault-init이 자동 주입하는 서비스: `cowork-authorization`, `cowork-notification`, `cowork-voice`, `cowork-chat`

### application.yml 작성 원칙

민감한 값은 반드시 환경변수로 주입합니다.

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

### Spring Config Client (Go / Elixir 서비스)

Go 서비스와 Elixir 서비스도 `cowork-config` Config Server에서 설정을 받아옵니다.<br>
각 서비스의 config 패키지(`internal/config/springconfig/`)에 구현된 HTTP 클라이언트를 사용합니다.

### Flyway + JPA 설정 (Spring Boot 서비스 공통)

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

루트의 `docker-compose.yml`로 모든 인프라를 한 번에 띄웁니다.

```bash
# 전체 기동
docker compose up -d

# 특정 서비스만 기동
docker compose up -d mysql mongodb kafka
```

**인프라 포트 정보**

| 서비스 | 호스트 포트 | 비고 |
|---|---|---|
| MySQL | 3306 | 서비스별 DB 자동 생성 (6개) |
| PostgreSQL | 5432 | cowork-preference 전용 |
| MongoDB | 27017 | cowork-chat, cowork-voice |
| Kafka | 9094 | 호스트 접근용 (컨테이너 간: 9092) |
| Kafka UI | 8090 | 브라우저에서 토픽/메시지 확인 |
| Vault | 8200 | 시크릿 관리 (토큰: `dev-root-token`) |
| MinIO | 9000 | S3 호환 오브젝트 스토리지 |
| MinIO Console | 9002 | 브라우저 UI |
| Elasticsearch | 9200 | 채팅 메시지 검색 (cowork-chat) |
| Redis | 6379 | cowork-gateway (세션), cowork-voice |
| LiveKit | 7880 | 음성 서버 |
| Prometheus | 9090 | 메트릭 수집 |
| Grafana | 3001 | 모니터링 대시보드 |
| Loki | 3100 | 로그 수집 |

MySQL 최초 기동 시 `docker/mysql/init.sh`가 자동 실행되어 서비스별 스키마를 생성합니다
(`cowork_authorization`, `cowork_user`, `cowork_team`, `cowork_project`, `cowork_channel`, `cowork_notification`).<br>
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

### 3단계 — 애플리케이션 서비스 기동 순서

MSA 서비스 간 의존성이 있으므로 아래 순서로 기동합니다.

```
1. cowork-config   (Eureka + Config Server — 가장 먼저 기동)
2. cowork-gateway  (Config Server에 등록 후 기동)
3. 비즈니스 서비스  (authorization, user, team, project, channel, preference, notification, chat, voice — 순서 무관)
```

**서비스 포트 정보**

| 서비스 | 포트 | 스택 |
|---|---|---|
| cowork-config | 8761 | Kotlin (Spring Cloud Config + Eureka) |
| cowork-gateway | 8080 | Kotlin (Spring Cloud Gateway) |
| cowork-authorization | 8081 | Go |
| cowork-user | 8082 | Elixir |
| cowork-channel | 8083 | Kotlin (Spring Boot) |
| cowork-voice | 8084 | Go |
| cowork-team | 8085 | Kotlin (Spring Boot) |
| cowork-notification | 8086 | Go |
| cowork-chat | 8087 | NestJS (TypeScript) |
| cowork-project | 8089 | Kotlin (Spring Boot) |
| cowork-roadmap | 8088 | Java (Spring Boot WebFlux + R2DBC) |
| cowork-preference | 9001 | Kotlin (Vert.x) |

### Makefile 명령어

```bash
make version   # 현재 버전 출력
make bump      # 버전 자동 증가
make setup     # Go 서비스 swagger 생성 및 의존성 설치
make init-logs # 로그 디렉터리 초기화
```

---

## Swagger / 모니터링

### Swagger (Gateway 경유)

Gateway는 서비스별 OpenAPI 문서를 아래 경로로 프록시합니다.

- `/v3/api-docs/{service}` (예: `/v3/api-docs/channel`)

지원 서비스: `authorization`, `user`, `team`, `channel`, `voice`, `chat`, `notification`, `preference`

로컬에서 Swagger UI는 `http://localhost:8080` (cowork-gateway)에서 확인합니다.

### Prometheus / Grafana

`docker-compose.yml`에서 Prometheus/Grafana가 함께 기동되며, Prometheus는 `cowork-monitoring/prometheus/prometheus.yml`에 정의된 타겟을 스크랩합니다.

- Grafana: `http://localhost:3001`
- Prometheus: `http://localhost:9090`
