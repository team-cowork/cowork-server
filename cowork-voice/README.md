# cowork-voice

## 역할

LiveKit 기반 음성 채널과 1:N 라이브 룸을 관리합니다.

- 음성·라이브 룸 참가·퇴장, 참가자와 세션 관리
- LiveKit access token 발급과 참가 권한 확인
- LiveKit webhook 상태 동기화와 음성·라이브 이벤트 발행

## 스택

- Go / Chi
- Go modules + Makefile
- LiveKit Server SDK / MongoDB
- Kafka / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8089`        | `8089`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                   |
|--------------------------|-----------------------------|--------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                          |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_VOICE_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_VOICE_PASSWORD`에서 전달 |
