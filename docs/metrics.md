# 메트릭

운영 서버는 Prometheus 형식으로 메트릭을 노출한다.

- 경로: `GET /actuator/prometheus` (운영 프로필에서만 노출)
- 모든 메트릭에는 `application=cowork-server` 태그가 붙는다.
- Nginx는 이 경로를 외부에 열지 않는다. 같은 VPC의 수집 서버에서만 호출한다.

## 직접 추가한 메트릭

| 이름 | 종류 | 설명 |
|---|---|---|
| `cowork_chat_messages_total{source}` | 카운터 | 저장된 메시지 수. `source`는 `user` 또는 `webhook` |
| `cowork_voice_participants` | 게이지 | 현재 음성 채널 참여자 수 |

## 자주 보는 기본 메트릭

| 이름 | 용도 |
|---|---|
| `http_server_requests_seconds` | API별 응답 시간과 상태 코드 |
| `hikaricp_connections_active` | DB 커넥션 사용량 |
| `jvm_memory_used_bytes` | 힙 사용량 |
