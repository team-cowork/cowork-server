# cowork-user

Elixir 기반 사용자 관리 서비스.

## 역할
- 사용자 계정/프로필 조회 및 수정
- 사용자 검색
- MinIO 기반 프로필 이미지 presigned URL 발급/확정
- Kafka `user.data.sync` 이벤트 소비
- Eureka 등록 및 heartbeat

## 런타임 계약
- 포트: `8082`
- Eureka 앱명: `cowork-user`
- Health: `/actuator/health`
- Metrics: `/actuator/prometheus`
- OpenAPI: `/v3/api-docs`
- Swagger UI: `/swagger-ui.html`
- Config Server: `APP_CONFIG_URL` + `APP_PROFILE`
- DB env: `DATABASE_URL`, `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`

## DB
- MySQL
- 기존 Flyway 이력과 테이블 유지
- 기동 시 `flyway migrate` 자동 실행

## 로컬 검증 메모
- `docker compose` 기준으로 `user.data.sync` 토픽 생성 후 Kafka 소비까지 검증됨
- 검증 payload는 `user_id`, `name`, `email`, `sex`, `major` 등 기존 sync 필드를 수용
