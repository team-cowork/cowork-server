# FCM 개별 전송 실패의 선택적 재시도

- **서비스**: cowork-notification
- **우선순위**: 🔴 높음
- **현재 상태**: FCM multicast 요청 자체가 성공하면 재시도 가능한 개별 token 실패를 경고 로그만 남기고 알림 처리를 성공으로 종료함
- **관련 작업**: [채팅 알림 전달의 종단간 멱등성 보장](../30-reliability/notification-delivery-idempotency.md)

## 문제

`cowork-notification/internal/infra/fcm/sender.go`의 `Sender.Send`는 최대 500개 token씩 `SendEachForMulticast`를 호출한다. 호출 자체가 성공하면 각 `SendResponse`를 순회하지만, unregistered 오류만 invalid token 목록에 추가하고 그 밖의 개별 실패는 `fcm send failed` 경고만 기록한다. 이 경우 반환 오류는 `nil`이고 어떤 token이 성공했거나 재시도가 필요한지 호출자가 알 수 없다.

`cowork-notification/internal/domain/token/service.go`는 invalid token만 삭제한 뒤 성공을 반환한다. 이어서 `cowork-notification/internal/infra/kafka/consumer.go`의 notification consumer는 처리를 완료하고 `notification.trigger` offset을 commit한다. 따라서 FCM이 top-level 응답은 성공으로 반환했지만 일부 token에 일시 오류를 준 경우 해당 token의 알림은 다시 시도되지 않는다. 반대로 원본 trigger 전체를 재처리하면 이미 성공한 token에도 같은 푸시를 다시 보낼 수 있다.

`cowork-notification/internal/infra/fcm/sender_test.go`는 `temporary-error-token`의 개별 실패에도 `require.NoError`와 빈 invalid 목록을 기대해 현재 유실 동작을 고정한다. `cowork-notification/src/main/resources/db/migration/`의 `V1`부터 `V6`까지에는 device token과 projection 상태만 있고 선택적 전송 재시도를 보존할 durable table은 없다.

## 전송 결과와 재시도 정책

| 결과 | 판정 | 후속 처리 |
|------|------|-----------|
| 성공 | 개별 `SendResponse.Success`가 참임 | 완료로 기록하고 같은 delivery에서 다시 보내지 않음 |
| invalid | unregistered 등 재사용할 수 없는 token 오류임 | token을 제거하고 재시도하지 않음 |
| retryable | timeout, rate limit, provider 일시 장애 등 재시도 가능한 개별 오류임 | 해당 token delivery만 durable queue에 저장함 |
| 미분류 오류 | 영구 실패 여부를 안전하게 판정할 수 없음 | 제한된 재시도 뒤 격리하고 원인과 건수를 노출함 |
| multicast top-level 오류 | 개별 성공 여부를 받지 못함 | 영향받은 batch의 상태를 별도로 기록하고 provider 오류 분류에 따라 재시도함 |

재시도 단위는 원본 논리 알림의 안정적인 `eventId`와 device token을 함께 식별해야 한다. `(event_id, device_token_id)`를 canonical delivery key와 unique 제약으로 사용하고, `topic`, `partition`, `offset`은 수신 이력과 진단 정보로만 보존한다. 같은 논리 이벤트가 다른 offset으로 재발행될 수 있으므로 Kafka 좌표를 멱등성 key로 사용하지 않는다. upstream event에 안정적인 `eventId`가 없다면 offset에서 새 ID를 만들지 않고 먼저 event identity 계약을 보강한다. 성공·invalid로 판정된 token은 retryable 집합에 다시 포함하지 않는다. FCM 호출 성공 직후 process가 종료되는 구간까지 완전한 exactly-once 전송을 보장할 수는 없으므로, 이 crash window와 client collapse 정책도 별도로 문서화한다.

## 할 일

### 결과 모델과 오류 분류

- `FCMSender.Send`가 successful, invalid, retryable token 집합을 담은 구조화 결과를 반환하도록 port와 구현을 변경한다.
- Firebase Messaging 오류 code를 invalid, retryable, 격리 대상에 명시적으로 매핑하고 알 수 없는 오류의 기본 정책을 정한다.
- batch 경계를 넘어가도 응답 index가 원래 token과 정확히 대응하도록 결과 조립을 검증한다.
- token 원문을 로그와 metric label에 남기지 않고 결과 유형별 개수만 관측한다.

### durable 선택 재시도

- 후속 migration으로 `event_id`, `device_token_id`, 상태, 시도 횟수, `next_attempt_at`, 마지막 오류 분류를 저장하는 재시도 table과 `(event_id, device_token_id)` unique 제약을 추가한다.
- 첫 FCM 호출 전에 선택된 모든 device token의 pending delivery를 canonical key로 적재하고, 같은 `eventId`가 다른 Kafka offset으로 재전달되어도 새 전송 대신 기존 non-terminal delivery를 이어서 처리한다.
- retryable 결과를 durable하게 저장한 뒤에만 원본 `notification.trigger` 처리를 성공으로 반환하고 offset을 commit한다.
- 재시도 worker가 retryable token만 전송하고 exponential backoff, jitter, 최대 시도 횟수, 격리 상태를 적용한다.
- 재시도 시 device token이 삭제되거나 다른 계정으로 이전된 경우의 취소 정책을 정의한다.
- 성공·invalid 상태를 멱등하게 finalize해 worker 재시작이나 중복 claim이 이미 성공한 token을 다시 선택하지 않게 한다.
- 대기 건수, 최고 지연, 결과 유형, 격리 건수, 재시도 성공률 metric을 추가한다.

## 검증

- 한 multicast 응답에 success, unregistered, transient error를 함께 넣고 세 결과 집합이 정확한 token을 포함하는지 단위 테스트한다.
- transient 개별 실패가 발생하면 성공 token은 한 번만 호출되고 실패 token만 재시도되는지 검증한다.
- 같은 `eventId`를 원래 record 재전달과 다른 offset의 재발행으로 각각 처리해도 completed delivery의 token이 새 전송 대상으로 선택되지 않는지 확인한다.
- retry row 저장 실패 시 원본 Kafka offset이 commit되지 않는지 consumer 통합 테스트한다.
- retry worker를 전송 전·후와 상태 finalize 전·후에 중단하고 재시작해 claim과 상태가 복구되는지 확인한다.
- 최대 시도 횟수에 도달한 token이 무한 loop 없이 격리되고 운영 metric에 나타나는지 확인한다.
- `cowork-notification`에서 `go test ./...`를 실행해 sender, token service, Kafka consumer 회귀 테스트가 통과하는지 확인한다.

## 완료 조건

- FCM 개별 응답의 성공, invalid, retryable 결과가 호출 계층에 보존되어 있다.
- 재시도 가능한 개별 실패는 Kafka offset commit 전에 durable retry 대상으로 저장되어 있다.
- `(eventId, deviceTokenId)`가 Kafka 좌표와 무관한 canonical delivery identity로 사용된다.
- 부분 실패 재시도가 이미 성공한 token을 다시 전송 대상으로 선택하지 않는다.
- invalid token은 제거되고 재시도 상한을 넘은 실패는 격리되어 있다.
- 선택 재시도의 적체와 성공·실패 상태를 테스트와 metric으로 확인할 수 있다.
