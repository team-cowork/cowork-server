# cowork-chat

## 역할
채팅 서비스.
- Socket.io 기반 실시간 메시지 송수신
- 채팅 메시지 저장/조회
- MinIO presigned URL 기반 첨부파일 업로드

## 스택
- NestJS 11 + TypeScript
- Socket.io
- MongoDB (Mongoose)
- KafkaJS
- MinIO

## 포트
`8087`

## 의존성
- Kafka produce: `chat.message`, `notification.trigger`
- MinIO (파일 업로드)

## 환경변수
| 변수 | 설명 |
|---|---|
| `MONGODB_URI` | MongoDB 연결 URI |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 브로커 주소 |
| `MINIO_INTERNAL_ENDPOINT` | MinIO 내부 엔드포인트 |
| `MINIO_ACCESS_KEY` | MinIO 액세스 키 |
| `MINIO_SECRET_KEY` | MinIO 시크릿 키 |
| `MINIO_BUCKET` | MinIO 버킷명 |
