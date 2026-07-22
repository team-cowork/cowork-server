# cowork-preference

## 역할

계정·팀·프로젝트·채널 설정과 사용자 정의 역할을 관리하는 Vert.x 서비스입니다.

- 계정 상태와 만료 시간, 테마·언어·날짜/시간 표시 설정
- 팀 설정과 텍스트/음성 채널 설정
- 계정별 채널 알림 on/off 설정
- 프로젝트 역할 정의·멤버 역할 할당
- 팀 사용자 정의 역할 정의·수정·할당과 멤버별 역할 조회
- 설정 Redis 캐시와 상태 만료 처리
- 상태·팀 설정 변경 이벤트 발행

## 스택

- Kotlin 2.4 / Java 25 + Vert.x 5 Coroutines
- Vert.x PostgreSQL Client + PostgreSQL + Flyway
- Vert.x Redis Client, Vert.x Kafka Client
- Eureka Client, Micrometer Prometheus
- Amper(`module.yaml`)

## 포트와 엔드포인트

- 포트: `9001`
- API: `/preferences/**`
- Health: `/health`
- Prometheus: `/metrics`
- OpenAPI JSON: `/swagger/doc.json`

API 전체 경로는 `src/main/resources/openapi.json`에서 확인합니다.

## 이벤트와 의존성

- Kafka produce: `preference.status.changed`, `preference.team.setting.changed`
- PostgreSQL: 설정과 팀·프로젝트 역할
- Redis: 리소스 설정과 채널 알림 캐시
- Config Server: 기동 시 필수(3회 조회 실패 후 종료)
- Eureka: 서비스 등록과 heartbeat

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `CONFIG_SERVER_URL`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, PostgreSQL host/DB/schema/pool, Redis, Kafka, Eureka |
| Vault | `preference.db.username`, `preference.db.password` |

Config Server를 3회 조회하지 못하면 종료합니다. `PORT`, `POSTGRES_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `EUREKA_*` 직접 환경변수는 중앙 설정보다 높은 우선순위로 적용됩니다.
