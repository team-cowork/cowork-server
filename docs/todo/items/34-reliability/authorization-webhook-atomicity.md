# Authorization 웹훅 멱등 처리와 outbox 원자화

- **서비스**: cowork-authorization
- **우선순위**: 🔴 높음
- **현재 상태**: `POST /events/datagsm`가 처리 여부 조회, 학생별 Kafka 직접 발행, 처리 완료 기록을 서로 분리해 실행함

## 문제

`cowork-authorization/internal/service/event_service.go`의 `ProcessEvent`는 webhook `event_id`로 `ProcessedEventStore.Exists`를 먼저 조회한다. 미처리 event이면 `data.new[]`를 여러 `user.data.sync` 메시지로 만든 뒤 `EventPublisher.Publish`를 순차 호출하고, 모든 호출이 끝난 다음 `MarkProcessed`를 실행한다. 조회와 완료 기록 사이에 원자적인 claim이 없어 동일 event의 동시 요청 두 개가 모두 미처리 상태를 관측하고 같은 batch를 발행할 수 있다.

여러 학생을 포함한 batch의 중간 publish가 실패하면 앞에서 성공한 메시지만 Kafka에 남고 처리 완료 row는 생성되지 않는다. webhook 재시도는 앞 항목부터 다시 발행한다. 모든 publish가 성공한 뒤 `MarkProcessed`가 실패해도 오류를 로그로만 남기고 `nil`을 반환하므로 `POST /events/datagsm`는 `200`을 응답한다. 이후 같은 event가 다시 전달되면 처리 기록이 없어 batch 전체가 중복 발행된다.

`V3__add_processed_events.sql`의 `tb_processed_events.event_id` primary key와 `V4__add_kafka_outbox.sql`·`V5__add_kafka_outbox_partition.sql`의 `tb_kafka_outbox`는 이미 존재한다. 그러나 `cowork-authorization/cmd/main.go`는 webhook service에 outbox writer가 아니라 Kafka `Producer`를 직접 주입하며, `V8`까지의 후속 migration에도 webhook event와 item을 outbox row에 연결하는 unique key는 없다. `cowork-authorization/internal/service/event_service_test.go`는 처리 완료 기록 실패를 성공으로 허용하는 동작을 명시적으로 검증하고 있으며 동시 요청, transaction rollback, relay 재시작 테스트는 없다.

## 원자 처리 정책

| 단계 | 목표 동작 |
|------|-----------|
| 검증 | 서명, envelope, `data.new[]` 전체와 item index 중복을 database 변경 전에 검증함 |
| inbox claim | `event_id` unique insert로 한 요청만 event를 claim함 |
| outbox 적재 | claim과 모든 item outbox row를 하나의 MySQL transaction에서 commit함 |
| item identity | `(event_id, event_index)`를 unique delivery identity로 사용함 |
| 중복 요청 | 이미 commit된 `event_id`이면 새 outbox row 없이 성공을 반환함 |
| 응답 | inbox와 batch outbox transaction이 commit된 뒤에만 `200`을 반환함 |
| relay | 기존 at-least-once relay의 crash 중복은 동일 item identity를 보존하고 소비자 멱등성으로 흡수함 |

## 할 일

### inbox와 outbox transaction

- `Exists` 선조회와 publish 후 `MarkProcessed` 흐름을 제거하고 repository의 단일 transaction API로 교체한다.
- 기존 `tb_processed_events`를 unique inbox claim으로 전환하거나 동등한 inbox table을 후속 migration으로 추가한다.
- inbox에 payload hash를 보존해 같은 `event_id`로 내용이 다른 재전달을 정상 중복과 구분한다.
- `tb_kafka_outbox`에 nullable source event ID와 item index를 추가하고 webhook row에 `(source_event_id, source_event_index)` unique 제약을 적용한다.
- inbox insert와 모든 `data.new[]` outbox insert를 같은 transaction에서 실행하고 하나라도 실패하면 전체를 rollback한다.
- 같은 payload 안의 중복 `event_index`와 같은 `event_id`에 다른 payload가 들어오는 충돌을 명시적으로 거부하거나 격리한다.
- `tb_processed_events` retention과 outbox·provider 재전달 기간을 맞춰 완료 기록이 너무 일찍 제거되지 않게 한다.

### 서비스와 relay 계약

- `EventService`에서 Kafka `Producer` 직접 의존성을 제거하고 transaction coordinator를 주입한다.
- `userSyncMessage`의 `event_id`와 `event_index`를 outbox source identity와 일치시킨다.
- 처리 완료 기록 실패를 성공으로 삼는 기존 테스트를 rollback·오류 응답 계약으로 변경한다.
- 기존 `OutboxRelay`가 webhook outbox row를 발행하고 실패·재시작 뒤에도 같은 identity를 유지하는지 확인한다.
- inbox duplicate, transaction rollback, outbox relay 결과를 event ID 원문 노출 없이 metric으로 집계한다.

## 검증

- 같은 `event_id`를 두 goroutine에서 동시에 처리해 inbox row 하나와 item 수만큼의 outbox row만 생성되는지 MySQL 통합 테스트한다.
- batch의 두 번째 outbox insert에 실패를 주입해 inbox와 첫 번째 outbox row까지 모두 rollback되는지 확인한다.
- transaction commit 직전·직후 process 중단과 재요청을 재현해 부분 batch가 생성되지 않는지 검증한다.
- 이미 commit된 webhook을 다시 보내도 outbox row 수가 증가하지 않고 `200`을 반환하는지 handler 테스트한다.
- outbox publish 성공 후 delete 전 relay를 중단해 재발행된 메시지가 동일한 `event_id`와 `event_index`를 유지하는지 확인한다.
- `cowork-authorization`에서 `go test ./...`를 실행해 webhook, migration, outbox relay 회귀 테스트가 통과하는지 확인한다.

## 완료 조건

- webhook inbox claim과 batch outbox 적재가 하나의 database transaction으로 commit되어 있다.
- 동일 `event_id`의 동시·순차 재요청이 새 outbox batch를 만들지 않는다.
- batch 중간 실패나 process crash가 일부 item만 durable하게 남기지 않는다.
- 모든 발행 item에 안정적인 `event_id`와 `event_index` identity가 부여되어 있다.
- 웹훅 요청 경로가 Kafka에 직접 publish하지 않는다.
