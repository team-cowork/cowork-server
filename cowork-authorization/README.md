# cowork-authorization

## 역할
인증 서비스.
- JWT 액세스 토큰 / 리프레시 토큰 발급 및 갱신
- DataGSM OAuth2 PKCE 로그인
- 회원가입 / 로그인 처리

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
