# cowork-authorization

## 역할
인증 서비스.
- JWT 액세스 토큰 / 리프레시 토큰 발급 및 갱신
- DataGSM OAuth2 PKCE 로그인
- 회원가입 / 로그인 처리
- `/auth/token`, `/auth/refresh` 응답 시 access token을 JSON 본문뿐 아니라 `cowork_ws_token` 쿠키(httpOnly, Secure, `Path=/ws`)로도 발급 — `/ws/{module}` 웹소켓(예: `cowork-chat`의 `/ws/chat`) 인증을 Gateway가 처리할 수 있도록 지원 (`/auth/signout`에서 쿠키 삭제)

## 스택
- Go + Gin
- GORM + MySQL

## 포트
`8081`

## 의존성
- Eureka Client

## 환경변수
| 변수 | 설명 |
|---|---|
| `DB_DSN` | MySQL DSN |
| `DATAGSM_CLIENT_ID` | DataGSM 클라이언트 ID |
| `JWT_SECRET` | JWT 시크릿 키 |
