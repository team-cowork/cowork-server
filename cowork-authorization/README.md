# cowork-authorization

## 역할
인증 서비스.

- JWT 액세스 토큰 / 리프레시 토큰 발급 및 갱신
- DataGSM OAuth2 PKCE 로그인
- 회원가입 / 로그인 처리

## 스택
TBD (Spring Boot + Java/Kotlin, .NET 등 팀 결정)

## 포트
`8081`

## DB
MySQL — Flyway로 스키마 관리 (`db/migration/V1__init.sql`)
