# Preference Redis cache 실패 격리

- **서비스**: cowork-preference, Redis
- **우선순위**: 🔴 높음
- **현재 상태**: authoritative DB·outbox 작업이 성공한 뒤 Redis 쓰기 실패가 요청·consumer 실패로 전파됨

## 문제

`PreferenceService.getSettings`는 cache miss 시 PostgreSQL에서 설정을 읽은 뒤 `PreferenceCache.setSettings`가 성공해야 응답한다. `updateSettings`는 설정과 outbox event를 database transaction으로 commit한 다음 Redis 쓰기를 기다리고, 실패하면 성공 결과를 반환하지 못한다. 따라서 선택적 가속 계층인 Redis 장애가 Preference 읽기·쓰기 전체 장애로 확대된다.

특히 `PreferenceHandler.updateSettings`는 service가 `Result`를 반환한다고 가정하고 호출 자체를 `runCatching`으로 감싸지 않는다. commit 뒤 cache 예외가 발생하면 응답이 완료되지 않거나 상위 timeout으로 끝날 수 있고, 클라이언트 재시도는 이미 반영된 상태와 event를 다시 만들 수 있다.

`GithubRepoSettingCommandProcessor`도 inbox·상태·result outbox를 commit한 뒤 cache set/invalidate를 수행한다. Redis 실패를 consumer가 1초마다 재시도하면 이미 처리된 command의 replay 경로가 result outbox를 매번 새로 적재한 뒤 다시 cache에서 실패할 수 있다. cache 장애가 outbox 증식과 Kafka 중복 결과로 이어지는 구조다.

## 실패 정책

| 경로 | Redis 실패 시 목표 동작 |
|------|-------------------------|
| 단건·bulk 조회 | PostgreSQL 결과를 반환하고 cache 실패를 metric·로그로 기록함 |
| HTTP 설정 변경 | DB·outbox commit 성공을 API 성공 기준으로 삼음 |
| Kafka command | durable inbox·outbox 성공 뒤 offset을 전진시키고 cache 복구는 별도로 수행함 |
| stale cache | 짧은 TTL, version 비교 또는 durable invalidation repair로 수렴시킴 |

## 할 일

### cache-aside 경계

- cache get·set·invalidate를 best-effort wrapper로 감싸고 database를 authoritative source로 유지한다.
- cache 실패를 민감한 설정값 없이 구조화 로그와 metric으로 기록한다.
- bulk 경로의 순차 Redis 호출을 pipeline 또는 제한된 병렬 처리로 바꾼다.
- cache 복구 뒤 stale 값을 제거하거나 최신 version으로 덮어쓰는 repair 방식을 구현한다.

### command 멱등성

- command 처리 성공 여부를 post-commit cache 결과와 분리한다.
- 같은 `operationId`의 result outbox event가 cache 재시도로 중복 생성되지 않게 unique key 또는 enqueue 멱등성을 추가한다.
- Redis 장애 동안 누적된 repair 작업의 상한과 재시도 backoff를 둔다.
- handler가 예상하지 못한 service 예외에도 항상 명시적인 HTTP 응답을 끝내게 한다.

## 검증

- cache 오류가 authoritative read·write 결과를 덮어쓰지 않는지 호출 경계와 예외 흐름을 정적으로 점검한다.
- DB·outbox commit과 cache set·invalidate의 transaction 경계를 코드와 schema로 확인한다.
- Redis 장애 중 result outbox 증가율, cache 실패율, repair 적체와 수렴 시간은 metric과 통제된 운영 점검으로 확인한다.
- 로그와 metric label에 설정 원문이 포함되지 않는지 로깅 지점을 정적으로 점검한다.
- Redis, database, Kafka command 재처리를 구동하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- Redis 장애가 Preference의 authoritative read·write와 Kafka command 완료를 실패시키지 않는다.
- commit된 변경이 cache 오류 때문에 클라이언트 재시도를 유발하지 않는다.
- 동일 command의 result outbox가 cache 재시도로 중복 적재되지 않는다.
- stale cache의 수렴 시간과 복구 상태를 metric으로 확인할 수 있다.
