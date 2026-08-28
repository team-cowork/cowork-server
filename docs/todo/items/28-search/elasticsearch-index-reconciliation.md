# MongoDB-Elasticsearch 메시지 색인 정합성 복구

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: Elasticsearch 쓰기 실패가 로그로만 남고 MongoDB와 검색 색인의 불일치를 복구하는 durable 재시도·재구축 경로가 없음

## 문제

`cowork-chat/src/chat/kafka/chat-message.consumer.ts`의 `ChatMessageConsumer.handleMessageEvent`는 메시지를 MongoDB에 저장하고 Socket.IO로 전송한 뒤 `ElasticsearchService.indexMessage`를 비동기로 호출한다. `ChatService.editMessage`, `ChatService.deleteMessage`, `ChatService.pinMessage`, `ChatService.unpinMessage`, `ChatService.deleteFile`도 MongoDB 변경을 완료한 뒤 Elasticsearch 갱신을 기다리지 않고 실행한다.

`cowork-chat/src/search/elasticsearch.service.ts`의 `ElasticsearchService.indexMessage`, `updateMessage`, `updatePinStatus`, `deleteMessage`는 Elasticsearch 오류를 내부에서 로그로 남기고 정상 반환한다. 특히 update의 `404`도 정상 종료하므로 최초 색인이 누락된 메시지는 이후 편집이나 고정 작업으로 복구되지 않는다. 호출부에는 실패 여부, 재시도 상태, 마지막 성공 버전이 전달되지 않는다.

메시지 검색은 `ElasticsearchService.searchMessages`와 `searchTeamMessages`가 Elasticsearch만 조회한다. 현재 MongoDB를 기준으로 누락 문서, 오래된 본문·고정 상태, 삭제 후 남은 문서를 찾아 고치는 backfill 또는 reconciliation 작업이 없으므로 일시적인 Elasticsearch 장애가 영구적인 검색 누락·오염으로 남을 수 있다.

## 정합성 모델

MongoDB 메시지를 원본으로 두고 Elasticsearch는 언제든 다시 만들 수 있는 파생 색인으로 정의한다. 실시간 쓰기는 비동기 eventual consistency를 유지하되 모든 변경 의도가 durable하게 남고, 재시도 또는 전체 재구축으로 수렴해야 한다.

| MongoDB 변경 | durable 색인 명령 | Elasticsearch 적용 규칙 | 복구 방식 |
|--------------|------------------|-------------------------|-----------|
| 메시지 생성 | 전체 문서 `UPSERT` | 동일 메시지·버전은 멱등하게 덮어씀 | 누락 문서를 재시도함 |
| 본문·고정 상태 변경 | 최신 전체 문서 `UPSERT` | 오래된 버전이 최신 상태를 되돌리지 못함 | `404`이면 새 문서로 복원함 |
| 메시지 삭제 | 삭제 tombstone | 이미 없는 문서는 성공으로 간주함 | 남은 색인 문서를 제거함 |
| 전체 재구축 | MongoDB snapshot scan | 새 물리 index에 bulk 색인함 | 검증 후 검색 alias를 교체함 |

MongoDB 변경과 색인 명령 기록 사이에 유실 구간이 생기지 않도록 같은 원자 경계에서 기록한다. hard delete 이후에도 삭제 의도를 재생할 수 있도록 별도 outbox 또는 tombstone이 남아야 한다.

## 할 일

### 증분 동기화

- 색인 대상 메시지의 생성·편집·고정·삭제와 함께 저장되는 durable 색인 outbox 모델을 설계한다.
- 메시지별 단조 증가 버전 또는 이에 준하는 순서 정보를 저장해 지연된 update가 최신 문서나 delete를 되돌리지 못하게 한다.
- outbox worker가 실패를 재시도하고 시도 횟수, 다음 시각, 마지막 오류, 완료 시각을 기록하게 한다.
- `ElasticsearchService`의 쓰기 메서드가 오류를 삼키지 않고 성공, 재시도 가능 오류, 영구 계약 오류를 호출자에게 구분해 반환하게 한다.
- partial update 대상이 없을 때 전체 MongoDB 문서로 upsert해 최초 색인 누락을 복원한다.
- 여러 replica가 같은 outbox 항목을 처리해도 결과가 같고 stale worker가 최신 상태를 덮지 않도록 claim과 버전을 적용한다.

### reconciliation과 재구축

- MongoDB를 커서 기반으로 순회해 색인 대상 전체 문서를 bulk upsert하는 재구축 작업을 추가한다.
- 재구축 중 실시간 변경을 놓치지 않도록 snapshot 기준점과 증분 outbox 적용 순서를 정의한다.
- 새 물리 index의 문서 수, 필수 필드, 샘플 내용, 누락·오류 건수를 검증한 뒤 검색 alias를 원자적으로 전환한다.
- MongoDB에 없는 Elasticsearch 문서를 제거할 수 있도록 delete tombstone 재생 또는 새 index 교체 방식을 사용한다.
- 재구축 실패 시 기존 검색 index를 유지하고 중단 지점부터 안전하게 재개하거나 새로 시작할 수 있게 한다.

### 관측

- outbox backlog 수, 가장 오래된 대기 시간, 재시도·영구 실패 수, 마지막 reconciliation 성공 시각을 노출한다.
- 검색 index 생성 실패를 애플리케이션 준비 상태와 어떻게 연동할지 정하고 검색 API가 빈 정상 응답으로 오판하지 않게 한다.
- 운영자가 실패 항목을 확인하고 재시도 또는 전체 재구축을 선택하는 절차를 문서화한다.

## 검증

- Elasticsearch를 중단한 상태에서 메시지 생성·편집·고정·삭제를 수행하고 복구 후 MongoDB 최종 상태로 색인이 수렴하는지 검증한다.
- 최초 `indexMessage`가 실패한 메시지도 이후 재시도로 검색 결과에 나타나는지 검증한다.
- 오래된 update를 최신 delete 뒤에 재전달해도 삭제된 문서가 되살아나지 않는지 검증한다.
- 전체 재구축 중 새 메시지와 편집이 발생해도 alias 전환 후 변경이 누락되지 않는지 검증한다.
- 두 worker가 같은 outbox 항목을 처리해도 중복 문서와 버전 역행이 생기지 않는지 검증한다.
- MongoDB에 없는 Elasticsearch 문서가 reconciliation 이후 검색 결과에서 제거되는지 검증한다.

## 완료 조건

- 모든 색인 대상 메시지 생성·편집·고정·삭제의 색인 의도가 durable하게 기록되어 있다.
- 일시적인 Elasticsearch 장애가 해소되면 별도 수동 데이터 수정 없이 색인이 MongoDB 최종 상태로 수렴한다.
- 누락·오래된·삭제 잔존 문서를 탐지하고 복구하는 증분 및 전체 재구축 경로가 마련되어 있다.
- Elasticsearch 쓰기 실패와 backlog가 지표 및 경고로 확인 가능하다.
- 재구축 중에도 검증되지 않은 새 index가 검색 트래픽에 노출되지 않는다.
