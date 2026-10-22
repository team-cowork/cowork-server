# cowork-github

## 역할

GitHub App으로 조직 저장소의 이슈·PR·라벨을 다루고 GitHub 이벤트를 cowork로 전달합니다.

- 채팅 명령으로 들어온 이슈 생성과 PR 머지·승인 처리
- 라벨 교체와 이슈 댓글 작성·수정·삭제 커맨드 처리와 결과 발행
- 저장소·이슈·댓글·라벨·PR 조회 내부 HTTP API
- GitHub App 설치·해제 연결과 저장소 Webhook 이벤트 발행

## 스택

- TypeScript / Node.js / NestJS
- npm / Nest CLI
- KafkaJS / Redis (ioredis)
- GitHub App (JWT · installation access token)

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8090`        | `8090`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                    |
|--------------------------|-----------------------------|---------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                           |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                 |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_GITHUB_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_GITHUB_PASSWORD`에서 전달 |
