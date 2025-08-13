# 인증

## DataGSM 로그인

cowork는 DataGSM OAuth2 인가 코드 방식(PKCE)으로 로그인합니다.

1. 클라이언트가 `code_verifier`를 만들고 DataGSM 로그인 페이지로 이동합니다.
2. 로그인 후 받은 인가 코드와 `code_verifier`를 `POST /auth/token`으로 보냅니다.
3. 서버는 DataGSM에서 토큰과 학생 정보를 받아 사용자를 저장하거나 갱신합니다.
4. 서버는 cowork 액세스 토큰과 리프레시 토큰을 발급합니다.

학생이 아닌 DataGSM 계정은 로그인할 수 없습니다.

## 토큰

| 토큰 | 형식 | 만료 | 저장 위치 |
|---|---|---|---|
| 액세스 토큰 | JWT (HS256) | 30분 | 클라이언트 메모리 |
| 리프레시 토큰 | 임의 문자열 | 14일 | 서버에는 SHA-256 해시만 저장 |

- 리프레시 토큰은 한 번 쓰면 폐기하고 새로 발급합니다.
- 만료된 리프레시 토큰은 매일 새벽 4시에 정리합니다.
- 로그아웃하면 해당 리프레시 토큰을 삭제합니다.

## 요청 인증

- HTTP 요청은 `Authorization: Bearer {accessToken}` 헤더를 보냅니다.
- STOMP 연결은 CONNECT 프레임의 `Authorization` 헤더로 인증합니다.
- SSE 구독은 브라우저가 헤더를 지정할 수 없으므로 `access_token` 쿼리 파라미터를 허용합니다.
- 다음 경로는 인증 없이 호출할 수 있습니다: `/auth/token`, `/auth/refresh`, Swagger 문서, `/ws/**`(STOMP CONNECT에서 인증), 외부 웹훅 수신 경로.
