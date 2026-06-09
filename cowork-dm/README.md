# cowork-dm

## 역할
다이렉트 메시지(DM) 서비스.
- Socket.io 기반 실시간 1:1 메시지 송수신
- DM 대화방 개설/조회/숨기기
- 메시지 수정/삭제, 이모지 리액션, 읽음 처리
- 사용자 차단/해제 (Redis 저장 + Kafka 이벤트)
- MinIO presigned URL 기반 첨부파일 업로드
- Outbox 패턴 기반 DM 알림 발행

## 스택
- NestJS 11 + TypeScript
- Socket.io
- MongoDB (Mongoose)
- Redis (ioredis)
- KafkaJS
- MinIO

## 포트
`8091`

## 의존성
- Kafka produce: `dm.block.updated`, DM 알림 이벤트
- Redis (차단 상태 저장)
- MinIO (파일 업로드)

## 환경변수
| 변수 | 설명 |
|---|---|
| `PORT` | HTTP 리슨 포트 |
| `MONGODB_URI` | MongoDB 연결 URI |
| `JWT_SECRET` | WebSocket JWT 검증 시크릿 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 브로커 주소 |
| `REDIS_HOST` | Redis 호스트 |
| `REDIS_PORT` | Redis 포트 |
| `MINIO_INTERNAL_ENDPOINT` | MinIO 내부 엔드포인트 |
| `MINIO_ACCESS_KEY` | MinIO 액세스 키 |
| `MINIO_SECRET_KEY` | MinIO 시크릿 키 |
| `MINIO_BUCKET` | MinIO 버킷명 |
| `EUREKA_SERVER_URL` | Eureka 서버 URL |
| `EUREKA_INSTANCE_HOST` | Eureka 등록 호스트 |
