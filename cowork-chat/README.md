# cowork-chat

## 역할
채팅 서비스.

- 실시간 메시지 송수신 (WebSocket)
- 채팅 메시지 저장 및 조회
- MinIO presigned URL 기반 첨부파일 업로드 준비
- Kafka: 메시지 이벤트 발행/소비 (`chat.message`, `notification.trigger`)

## 스택
TBD (NestJS, Go 등 팀 결정)

## 포트
`3000`

## DB
MongoDB (채팅 메시지 이력 저장)

## 파일 업로드

`POST /chat/channels/{channelId}/files/presigned-url`

- Gateway가 주입한 `X-User-Id` 기준으로 채널 멤버 여부를 확인한다.
- 응답의 `uploadUrl`로 클라이언트가 MinIO에 직접 `PUT` 업로드한다.
- 업로드 후 메시지 전송 시 `attachments.url`에는 응답의 `fileUrl`을 사용한다.
- 기본 파일 크기 제한은 100MB다.
- 기본 presigned URL 발급 제한은 사용자당 1분 20회다.

필수 환경 변수:

- `minio.endpoint`
- `minio.accessKey`
- `minio.secretKey`
- `minio.bucket`

대문자 환경 변수(`MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`)도 같이 지원한다.
