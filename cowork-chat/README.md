# cowork-chat

## 역할
채팅 서비스.

- 실시간 메시지 송수신 (WebSocket)
- 채팅 메시지 저장 및 조회
- Kafka: 메시지 이벤트 발행/소비 (`chat.message`, `notification.trigger`)

## 스택
TBD (NestJS, Go 등 팀 결정)

## 포트
`3000`

## DB
MongoDB (채팅 메시지 이력 저장)
