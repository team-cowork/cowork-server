# 종료 음성 세션의 Redis stale cache 차단

- **서비스**: cowork-voice
- **우선순위**: 🔴 높음
- **현재 상태**: MongoDB에서 종료된 음성 세션이 Redis eviction 실패나 경쟁 조건으로 최대 2시간 동안 active cache hit로 반환될 수 있음

## 문제

`cowork-voice/internal/infra/redis/session_repository.go`는 active 음성 세션 JSON을 channel, room name, session ID 기준의 Redis key 세 개에 `sessionTTL` 2시간으로 저장한다. `FindActiveSession`은 channel key의 cache hit를 받으면 cached object의 `status`나 현재 MongoDB 상태를 확인하지 않고 즉시 반환한다. TTL은 stale entry의 존속 시간을 제한할 뿐 종료된 세션이 그 시간 동안 사용되는 것을 막지 않는다.

`EndSession`과 `EndSessionAndEnqueue`는 MongoDB의 상태를 `ended`로 바꾼 뒤 세 Redis key를 삭제한다. 삭제 실패는 error log만 남기고 종료 결과는 성공으로 반환한다. 또한 cache miss 조회가 MongoDB의 active 세션을 읽은 직후 다른 요청이 종료 처리하면, 늦게 실행된 `cacheSession`이 eviction 뒤에 예전 active 값을 다시 쓸 수 있다.

`cowork-voice/internal/domain/voice_room/service.go`의 `Join`은 `FindActiveSession` 결과를 그대로 재사용해 LiveKit room을 준비하고 token을 발급한다. 따라서 stale hit가 있으면 MongoDB에서는 이미 종료된 session ID와 room name으로 재입장할 수 있고 새 active 세션 생성도 건너뛴다. 현재 단위 테스트는 입장 권한과 room lifecycle 같은 핵심 비즈니스 규칙을 다루며 Redis·MongoDB cache 일관성 메커니즘은 테스트 범위에서 제외한다. MongoDB-backed 서비스에는 SQL migration이 없고, 현재 model·index 코드에도 cache generation이나 durable invalidation 상태가 없다.

## cache 일관성 정책

| 상황 | 목표 동작 |
|------|-----------|
| active cache hit | cached session ID가 MongoDB의 현재 active session과 일치하고 status가 `active`일 때만 반환함 |
| MongoDB 종료 성공 | session generation을 종료 tombstone으로 전환하고 이전 generation의 cache write를 차단함 |
| Redis eviction 실패 | 요청 성공 여부와 무관하게 durable repair 대상으로 남기고 active 조회는 MongoDB를 우선함 |
| 늦게 도착한 cache write | session ID 또는 version 비교에 실패하면 tombstone을 덮어쓰지 못함 |
| 손상·unknown cache 값 | 해당 key를 우회하고 MongoDB 결과로 복구함 |
| 새 active 세션 생성 | 이전 session의 channel·room·session key와 구분되는 새 generation만 노출함 |

## 할 일

### stale read 차단

- `FindActiveSession`의 cache 값을 후보로만 취급하고 MongoDB의 현재 active session ID·status 또는 단조 증가 version으로 검증한 뒤 반환한다.
- cached `status`가 `active`가 아니거나 authoritative session과 ID·version이 다르면 세 관련 key를 제거하고 MongoDB 결과로 교체한다.
- `Join` 직전에 repository가 active 상태를 보장한다는 계약을 명시하고 종료 session으로 token을 발급하지 않게 한다.
- Redis 오류가 발생한 조회는 cache를 우회해 MongoDB를 authoritative source로 사용한다.

### tombstone과 repair

- 종료 update와 함께 session ID 또는 generation 기반의 durable cache invalidation marker를 MongoDB document에 기록한다.
- Redis에서 delete만 수행하는 대신 tombstone과 version 비교를 원자적으로 적용해 종료 전의 지연된 `cacheSession`이 active 값을 되살리지 못하게 한다.
- repair worker가 Redis 복구 뒤 channel, room name, session ID key를 정리하고 durable marker를 완료 상태로 바꾼다.
- repair에 backoff와 상한을 두고 pending marker 수, 최고 지연, stale 차단 횟수, repair 실패를 metric으로 노출한다.
- MongoDB schema field와 index가 필요하면 `cowork-voice/internal/domain/voice_room/model.go`와 `CreateIndexes`에 코드 기반으로 반영한다.

### 정적·운영 검증과 핵심 정책

- cache hit 검증, tombstone compare-and-set, eviction, repair의 조건을 repository 코드와 상태 전이표로 점검한다.
- stale 차단, repair 적체, Redis 복구 뒤 수렴은 metric과 통제된 운영 rehearsal로 확인한다.
- `Join`이 종료된 session으로 token을 발급하지 않는 핵심 판단은 repository mock을 사용한 서비스 단위 테스트로 검증한다.
- Redis test server, MongoDB repository, cache 경쟁·복구를 고정하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 검증

- authoritative session 확인과 tombstone/version 비교가 모든 cache hit·fill 경로에 적용되는지 정적으로 점검한다.
- 종료 session 재사용 차단은 `Join` 서비스 단위 테스트로 검증한다.
- eviction 실패, 지연 cache write, Redis 복구, 이전 generation repair 결과는 metric과 운영 rehearsal로 확인한다.
- 새 active session이 이전 generation의 repair 대상과 구분되는지 key·version 설계를 검토한다.

## 완료 조건

- MongoDB에서 ended인 음성 session은 Redis TTL과 무관하게 active 조회로 반환되지 않는다.
- eviction 실패와 지연된 cache write가 종료 tombstone보다 오래된 active 값을 되살리지 않는다.
- `Join`이 종료 session의 session ID와 room name으로 LiveKit token을 발급하지 않는다.
- durable invalidation marker가 Redis 복구 뒤 완료 상태로 수렴한다.
- stale 차단과 repair 동작이 metric으로 확인되고 복구 절차가 runbook에 명시되어 있다.
