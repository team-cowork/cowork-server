# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 Firebase 설치(FID) 관리
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

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_CONFIG_PROFILE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_NOTIFICATION_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_NOTIFICATION_PASSWORD`에서 전달 |
