# 요청 수 제한

트래픽이 늘면서 로그인 반복 시도와 웹훅 폭주가 다른 API 응답에 영향을 주는 일이 생겨 요청 수를 제한한다.

| 대상 | 기준 키 | 기본 허용 횟수 | 설정 |
|---|---|---|---|
| `POST /auth/token`, `POST /auth/refresh` | 요청 IP | 분당 20회 | `cowork.rate-limit.auth-per-minute` |
| `POST /webhooks/{webhookId}/{token}` | 웹훅 ID | 분당 60회 | `cowork.rate-limit.webhook-per-minute` |

- 제한을 넘으면 `429 Too Many Requests`와 공통 오류 응답을 돌려준다.
- 기록은 애플리케이션 메모리에만 두므로 서버를 여러 대로 늘리면 각 서버가 따로 센다.
- 지난 시간 창 기록은 1분마다 정리한다.
- Nginx가 `X-Forwarded-For`를 넘기고 `server.forward-headers-strategy=framework`가 켜져 있어 실제 클라이언트 IP로 센다.
