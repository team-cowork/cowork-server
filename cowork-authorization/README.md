# cowork-authorization

## 역할

DataGSM 로그인과 인증 토큰·로그인 세션을 관리합니다.

- OAuth2 PKCE 로그인과 JWT 발급·갱신·로그아웃
- 사용자 계정·프로필 소유 서비스인 cowork-user에 로그인 정보 동기화 요청
- DataGSM 사용자 변경 웹훅의 원자 접수·outbox 전달과 온라인·오프라인 상태 발행

## 스택

- Go / Gin
- Go modules + Makefile
- GORM / MySQL
- Kafka / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8081`        | `8081`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수             | 기본값                      | 설명                                                      |
|------------------|-----------------------------|-----------------------------------------------------------|
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결                                   |
| `APP_PROFILE`    | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, DataGSM endpoint, 토큰 TTL, Kafka, Eureka.
- Vault: `DB_DSN`, `JWT_SECRET`, `DATAGSM_CLIENT_ID`, `DATAGSM_WEBHOOK_SECRET`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

## DataGSM 웹훅

웹훅 배치의 접수 기록과 모든 발행 메시지를 같은 DB transaction으로 저장한다. `200 accepted`는 영속 접수 완료이며 Kafka 발행과 학생 정보 반영은 비동기로 진행한다. 같은 ID·같은 내용은 `200 duplicate`, 같은 ID·다른 내용은 `409`로 처리한다.

처리 기록은 30일 보관하고 발생 후 30일 이상 지난 이벤트의 신규 접수를 거부한다. 미발행 outbox가 있는 기록은 발행 완료까지 보존한다. 응답 코드, 입력 정책, 지표와 복구 절차는 [웹훅 운영 문서](../docs/authorization-webhooks.md)를 참고한다.
