# cowork-voice

## 역할
음성 채널 서비스.
- 음성 세션 생성/종료
- 음성 채널 참여자 관리

## 스택
- Go
- LiveKit
- MongoDB

## 포트
`8084`

## 의존성
- Kafka produce: `voice.session.event`

## 환경변수
| 변수 | 설명 |
|---|---|
| `MONGODB_URI` | MongoDB 연결 URI |
| `LIVEKIT_API_KEY` | LiveKit API 키 |
| `LIVEKIT_API_SECRET` | LiveKit API 시크릿 |
| `KAFKA_BROKERS` | Kafka 브로커 주소 |
