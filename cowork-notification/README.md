# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 Firebase 설치(FID) 관리
- SSE 기반 실시간 알림 스트림 제공

## 스택

- Go / Chi
- Go modules + Makefile
- GORM / SQLite
- Kafka / Firebase Admin SDK

## 포트

| 용도       | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------|---------------|--------------------------|
| HTTP / SSE | `8086`        | `8086`                   |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORT` | `8086` | HTTP와 SSE 포트 |
| `DB_PATH` | 없음 | 필수 SQLite 영속 파일 경로 |
| `FCM_CREDENTIALS_FILE` | 없음 | Firebase 서비스 계정 JSON 파일. JSON 환경변수보다 우선 |
| `FCM_CREDENTIALS_JSON` | 없음 | 파일을 지정하지 않을 때 필수인 Firebase JSON |
| `FCM_DATA_ONLY` | `false` | FCM data 메시지만 발행할지 여부 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker 목록 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | 로컬은 PLAINTEXT, 운영은 SASL_SSL |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SCRAM-SHA-256 또는 SCRAM-SHA-512 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | SASL_SSL에서 필수인 서비스 계정 |
| `KAFKA_SSL_CA_LOCATION` | 시스템 CA | Kafka CA PEM 파일 경로 |
