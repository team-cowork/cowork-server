# cowork-chat

## 역할

실시간 채팅과 첨부파일 공유·메시지 검색을 제공합니다.

- 채널 메시지·답글·고정·이모지 반응과 타이핑 알림
- 첨부파일 업로드, DM 목록·숨기기, 사용자 차단과 미읽 카운트
- Elasticsearch 전문 검색과 GraphQL 통합 검색
- GitHub 이슈 생성 슬래시 커맨드와 사용자 알림 발행
- Preference 설정을 Kafka로 받아 UUID URL로 외부 웹훅 메시지 수신: 공개·보안 모드, 웹훅별 요청 제한과 중복 전송 방지

## 스택

- TypeScript / Node.js / NestJS
- npm / TypeScript Compiler
- Socket.IO / GraphQL (Apollo)
- MongoDB (Mongoose) / Elasticsearch / Redis / KafkaJS
- SeaweedFS (S3 호환) / AWS SDK

## 포트

| 용도             | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------|---------------|--------------------------|
| HTTP / WebSocket | `8087`        | `8087`                   |

## 환경변수

설정은 환경변수로 직접 전달합니다. 운영에서는 Nomad가 비밀 파일과 환경변수를 주입하고 서비스 등록을 관리합니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `8087` | HTTP와 WebSocket 포트 |
| `APP_CONFIG_PROFILE` | `local` | `prod`에서는 외부 API 주소에 HTTPS를 요구하고 Redis adapter 사용 |
| `NODE_ENV` | 없음 | 운영은 `production` 지정 |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | 없음 | MongoDB 연결. 모두 지정하면 `MONGODB_URI`보다 우선 |
| `MONGODB_URI` | 없음 | 개별 DB 설정을 사용하지 않을 때 필수 |
| `MONGODB_DIRECT_CONNECTION` | `false` | MongoDB direct connection 설정 |
| `KAFKA_BOOTSTRAP_SERVERS` | 없음 | 필수. 쉼표로 구분한 broker 주소 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | `PLAINTEXT`, `SSL`, `SASL_SSL` 지원 |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SASL 사용 시 `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | `SASL_SSL`에서 필수 |
| `KAFKA_SSL_CA_LOCATION` | 시스템 CA | PEM CA 파일 경로. TLS 서버 인증서 검증 유지 |
| `REDIS_HOST`, `REDIS_PORT` | 포트 `6379` | Redis와 Socket.IO adapter 연결 |
| `ELASTICSEARCH_URL` | 없음 | 필수. 검색 서버 주소 |
| `ELASTICSEARCH_ENDPOINT` | 없음 | 선택. URL의 자격증명을 유지하면서 접속 origin을 Envoy upstream으로 변경 |
| `JWT_SECRET` | 없음 | WebSocket access JWT 검증 키 |
| `PUBLIC_API_BASE_URL` | 없음 | 외부에서 접근하는 Gateway 주소 |
| `S3_INTERNAL_ENDPOINT`, `S3_BUCKET` | 없음 | 필수. 내부 S3 주소와 bucket |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | 없음 | Chat prefix 권한을 가진 S3 자격증명 |
| `S3_REGION` | `us-east-1` | S3 서명 region |
| `DISCORD_WEBHOOK_URL` | 없음 | 선택. 업무 장애 알림 webhook |

Kafka projection과 Redis adapter가 준비되기 전에는 `/health/ready`가 `503`을 반환합니다. 서비스 검색과 배포에서 이 경로를 readiness로 사용합니다.
