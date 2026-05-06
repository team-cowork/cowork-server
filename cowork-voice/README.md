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
`9000`

## 의존성
- Kafka produce: `voice.session.event`
