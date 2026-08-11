# cowork-user

## 역할

사용자 계정과 공개 프로필을 관리하는 서비스입니다.

- 내 프로필 조회·수정과 상태 메시지 변경
- 사용자 단건·batch 조회와 팀 범위 사용자 검색
- SeaweedFS(S3 호환) presigned URL 기반 프로필 이미지 업로드·확정·삭제
- Kafka `user.data.sync`를 소비하여 DataGSM 사용자 정보 upsert
- Redis에 표시 이름 캐시

## 스택

- Elixir 1.18 + Plug/Cowboy
- Ecto + MySQL, Flyway(Docker entrypoint에서 migration 실행)
- brod(Kafka), Redix, ExAws S3(SeaweedFS)
- Eureka Client와 Spring Config 호환 클라이언트

## 포트와 엔드포인트

- 포트: `8082`
- API: `/users/**`
- Health: `/actuator/health`
- Prometheus: `/actuator/prometheus`
- OpenAPI JSON / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`

인증이 필요한 요청은 Gateway가 전달한 `X-User-Id`를 사용합니다. `PUT /users/{userId}`는 authorization 서비스의 동기화용 upsert 경로입니다.

## 의존성

- MySQL: 계정·프로필 데이터
- Kafka consume: `user.data.sync`
- Redis: 표시 이름 캐시
- SeaweedFS: 프로필 이미지
- HTTP: `cowork-team`(팀 범위 검색)
- Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, DB host/port/name와 Flyway URL, Kafka, S3(SeaweedFS) endpoint, Redis, Eureka, Team 서비스 URL |
| Vault | `DB_USERNAME`, `DB_PASSWORD`, `SECRET_KEY_BASE`, S3 access/secret key |

컨테이너 entrypoint가 Config Server 설정을 먼저 읽고 Flyway migration을 수행한 뒤 Phoenix release를 시작합니다. Config Server 조회 실패 또는 필수 DB/`SECRET_KEY_BASE` 누락 시 즉시 종료합니다.
