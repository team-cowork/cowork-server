# cowork-chat 검색 색인 운영

MongoDB 메시지가 원본이고 Elasticsearch는 언제든 다시 만들 수 있는 파생 색인입니다. 메시지 생성·편집·고정·삭제는
색인 아웃박스에 durable하게 남고, 워커가 이를 검색 alias `chat_messages`에 반영합니다. Elasticsearch 장애로 실패한
쓰기는 상한이 있는 백오프로 계속 재시도하므로 장애가 해소되면 별도 데이터 수정 없이 색인이 수렴합니다.

색인 상태는 `/metrics`에서 확인합니다.

| 지표 | 의미 |
|---|---|
| `cowork_chat_search_index_backlog{status}` | 상태별 색인 대기·실패 메시지 수 |
| `cowork_chat_search_index_tombstone_backlog{status}` | 상태별 삭제 tombstone 수 |
| `cowork_chat_search_index_oldest_pending_seconds` | 가장 오래 대기 중인 색인 항목의 지연 |
| `cowork_chat_search_index_write_total{operation,outcome}` | 색인 쓰기 시도 결과 (`APPLIED`/`SUPERSEDED`/`RETRYABLE`/`PERMANENT`) |
| `cowork_chat_search_index_last_rebuild_success_timestamp_seconds` | 마지막 전체 재구축 성공 시각 |
| `cowork_chat_search_index_ready` | 검색 alias 사용 가능 여부 |

`/health/ready`의 `searchIndex`는 점검용 정보이며 readiness를 막지 않습니다. Elasticsearch가 중단돼도 메시지 송수신은
계속되어야 하기 때문입니다. 대신 색인이 준비되지 않은 동안 검색 API는 빈 결과 대신 `503`을 반환합니다.

## 운영 절차

```bash
npm run ops:message-search-index status         # backlog·실패 건수·alias 대상·마지막 재구축 결과
npm run ops:message-search-index retry-failed   # 영구 실패(FAILED) 항목을 다시 대기 상태로
npm run ops:message-search-index rebuild        # 새 물리 index로 전체 재구축 후 alias 전환
npm run ops:message-search-index resume-rebuild # 중단된 재구축을 중단 지점부터 재개
```

판단 기준은 다음과 같습니다.

- `backlog`의 `PENDING`이 계속 늘어나면 Elasticsearch 장애입니다. 원인을 해소하면 워커가 스스로 따라잡습니다.
- `FAILED`가 있으면 `status`로 원인을 확인하고 매핑·문서 계약 문제를 고친 뒤 `retry-failed`를 실행합니다.
- 검색 결과가 MongoDB와 어긋나거나 색인에만 남은 문서가 의심되면 `rebuild`를 실행합니다. 검증을 통과한 index만
  alias에 연결되므로 재구축이 실패해도 기존 검색은 그대로 동작합니다.

`rebuild`는 새 물리 index(`chat_messages-{타임스탬프}`)를 만들어 MongoDB 전체를 색인하고, 재구축 중 발생한 변경과
삭제를 따라잡은 뒤 문서 수와 표본 문서의 필수 필드 존재 여부를 검증하고 alias를 전환합니다. 검색 alias 이름이 아직 물리 index인
초기 배포에서는 첫 `rebuild`가 alias로 승격하며, 이때만 짧은 검색 공백이 생깁니다.

| 변수                                       | 기본값 | 설명                                      |
|--------------------------------------------|--------|-------------------------------------------|
| `CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS`     | `7`    | 삭제 tombstone 보존 기간. 전체 재구축이 삭제를 재생할 수 있을 만큼 길어야 합니다 |
