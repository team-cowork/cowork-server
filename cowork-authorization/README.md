# cowork-authorization

## 역할

DataGSM 로그인과 인증 토큰·로그인 세션을 관리합니다.

- OAuth2 PKCE 로그인과 JWT 발급·갱신·로그아웃
- 사용자 계정·프로필 소유 서비스인 cowork-user에 로그인 정보 동기화 요청
- DataGSM 사용자 변경 웹훅의 원자 접수·outbox 전달과 온라인·오프라인 상태 발행

## 스택

- Go / Gin
- Go modules + Makefile
- GORM / MySQL
- Kafka / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8081`        | `8081`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                           |
|--------------------------|-----------------------------|----------------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                                  |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                        |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_AUTHORIZATION_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_AUTHORIZATION_PASSWORD`에서 전달 |
