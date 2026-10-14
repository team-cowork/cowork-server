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

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_PROFILE` | `local` | Compose의 `SPRING_PROFILES_ACTIVE`에서 전달 |
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_VOICE_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_VOICE_PASSWORD`에서 전달 |

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [voice 슬롯](../deploy/prod/slots/voice.json)에서 관리합니다.
[이미지 bootstrap](deploy/bootstrap.sh)이 공통 입력을 이 런타임의 환경변수로 변환합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)

LiveKit 미디어 서버의 포트는 이 서비스의 HTTP 포트와 별개입니다.
외부 포트와 TURN 제약은 [LiveKit 운영 문서](../deploy/config/livekit/README.md)를 따릅니다.
