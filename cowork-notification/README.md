# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 디바이스 토큰 관리
- SSE 기반 실시간 알림 스트림 제공

## 스택

- Go / Chi
- Go modules + Makefile
- GORM / MySQL
- Kafka / Firebase Admin SDK / Eureka / Config Server

## 포트

| 용도       | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------|---------------|--------------------------|
| HTTP / SSE | `8086`        | `8086`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수             | 기본값                      | 설명                                                      |
|------------------|-----------------------------|-----------------------------------------------------------|
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결                                   |
| `APP_PROFILE`    | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, Kafka topic·group, Eureka, FCM 파일 경로.
- Vault: `db.dsn`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

Firebase 서비스 계정 JSON은 환경변수가 아닌 Docker secret으로 `/run/secrets/firebase-credentials.json`에 읽기 전용 마운트해야 합니다.
