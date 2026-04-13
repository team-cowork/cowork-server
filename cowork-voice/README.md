# cowork-voice

## 역할
음성 채널 서비스.

- 음성 세션 생성/종료
- 음성 채널 참여자 관리
- Kafka: 음성 세션 이벤트 발행 (`voice.session.event`)

## 스택
Rust (Axum/Tokio 등 팀 결정)

## 포트
`9000`

## DB
MongoDB (음성 세션 로그 / 메타데이터)
