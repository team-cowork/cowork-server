# 채팅 알림 전달의 종단간 멱등성 보장

- **서비스**: cowork-chat, cowork-notification
- **우선순위**: 🟠 중간
- **현재 상태**: 메시지별 안정적인 이벤트 ID와 downstream inbox가 없어 crash·재전달 시 알림 처리와 unread cache 변경이 중복될 수 있음

## 문제

`cowork-chat/src/chat/kafka/notification-outbox.poller.ts`의 `NotificationOutboxPoller.processMessageAndUpdateStatus`는 `notification.trigger` 발행이 성공한 뒤 MongoDB 메시지의 `notificationStatus`를 `SENT`로 바꾼다. Kafka 발행 성공 직후 상태 저장 전에 프로세스가 종료되면 stale `PROCESSING` 회수 후 같은 메시지가 다시 발행된다.

`NotificationTriggerProducer.send`는 이벤트 ID와 Kafka key 없이 payload만 발행한다. KafkaJS idempotent producer는 한 producer session 안의 전송 재시도를 보호하지만, 애플리케이션이 다시 만든 두 개의 논리 이벤트를 같은 메시지로 식별하지 못한다.

`cowork-notification/internal/infra/kafka/consumer.go`의 `Consumer.handle`은 이벤트를 받은 뒤 FCM과 SSE 처리를 수행하고 마지막에 Kafka offset을 커밋한다. 처리 완료 후 커밋 전에 종료되면 record가 다시 전달되지만, `cowork-notification`에는 event ID를 유일 키로 기록하는 inbox나 수신자별 전달 ledger가 없다.

`MessageRepository.findPendingAndMarkProcessing`은 batch claim을 `notificationProcessingStartedAt`의 millisecond 시각으로 구분한다. 여러 poller가 같은 후보를 경쟁하고 같은 millisecond 값을 만들면 claim에 실패한 poller도 상대가 점유한 문서를 조회할 수 있다. 또한 unread cache 증가는 발행 전에 수행되고 `notificationRetryCount`만으로 중복을 피하므로 발행 성공 후 crash 구간에서는 같은 메시지로 다시 증가할 수 있다.

## 멱등성 경계

| 단계 | 안정적인 식별자·상태 | 보장할 결과 |
|------|----------------------|-------------|
| `cowork-chat` outbox claim | 메시지 ID 기반 event ID와 무작위 claim token | 한 시점에 한 worker만 같은 메시지를 처리함 |
| Kafka 발행 | event ID를 record key와 envelope에 포함함 | 재발행돼도 동일한 논리 이벤트로 식별됨 |
| `cowork-notification` 소비 | event ID unique inbox와 처리 lease | 동일 이벤트의 fan-out 상태가 하나만 생성됨 |
| 수신자 전달 | `(eventId, userId, channel)` 전달 상태 | 재시작 후 수신자별 처리 상태를 이어감 |
| unread cache | event ID guarded 변경 또는 멱등한 cache invalidation | 같은 메시지가 cache count를 두 번 증가시키지 않음 |

FCM과 SSE 같은 외부 전달 경계는 DB transaction과 원자적으로 묶을 수 없으므로 transport 재전달 가능성을 명시한다. event ID를 SSE payload와 FCM data에 포함해 수신 측 중복 제거가 가능하게 하고, 제공자가 지원하는 collapse 정책을 사용하되 외부 제공자의 정확히 한 번 전달을 보장한다고 표현하지 않는다.

## 범위

| 포함 | 제외 |
|------|------|
| source outbox claim, Kafka 재발행, downstream inbox, 수신자별 처리 상태, SSE/FCM event ID, unread 중복 방지 | FCM batch 안에서 개별 token의 transient 실패를 분류하고 선택 재시도하는 문제 |

FCM 개별 transient 실패 처리는 별도 TODO에서 다룬다. 이 문서는 동일한 논리 알림이 crash와 Kafka 재전달 때문에 새 이벤트처럼 처리되는 문제에만 집중한다.

## 할 일

### cowork-chat 발행 경로

- MongoDB 메시지 ID에서 결정적으로 생성되는 알림 event ID를 정의하고 재시도마다 재사용한다.
- `Message` outbox 상태에 무작위 claim token과 lease 시각을 저장하고, 실제 token을 소유한 worker만 `SENT`·`PENDING`·`FAILED`로 전환하게 한다.
- `MessageRepository.findPendingAndMarkProcessing`의 millisecond timestamp 동등 비교를 claim token 비교로 교체한다.
- `NotificationTriggerEvent`에 event ID와 계약 버전을 추가하고 `NotificationTriggerProducer.send`가 event ID를 Kafka key로 사용하게 한다.
- Kafka 발행 후 MongoDB 상태 저장이 실패해 재발행되더라도 downstream에서 같은 event ID로 합쳐지는지 보장한다.
- unread cache 변경을 event ID로 중복 방지하거나, 반복 실행해도 안전한 cache invalidation으로 전환한다.

### cowork-notification 소비 경로

- 새 migration으로 event ID unique inbox와 필요한 수신자별 전달 상태를 추가하고 기존 migration 파일은 수정하지 않는다.
- `Consumer.handleWithRetry`가 외부 전송 전에 inbox 처리 lease를 획득하고 Kafka 재전달 시 같은 상태를 재사용하게 한다.
- Kafka offset은 inbox와 수신자 처리 상태가 durable한 종결 상태에 도달한 뒤 커밋한다.
- 처리 도중 종료된 inbox lease를 회수하되 이미 완료된 수신자 fan-out을 새 작업으로 생성하지 않게 한다.
- SSE payload에 event ID를 포함하고 SSE 재연결·중복 record를 클라이언트가 구분할 수 있는 계약을 정한다.
- FCM data에 같은 event ID를 포함하고 가능한 플랫폼의 collapse·중복 제거 키를 일관되게 적용한다.
- inbox와 전달 ledger의 보존 기간, 재처리, 개인정보 최소 저장 범위를 정한다.

## 검증

- Kafka 발행 성공 후 `notificationStatus=SENT` 저장 전에 `cowork-chat`을 종료해도 동일 event ID만 발행되는지 검증한다.
- 두 poller가 같은 millisecond에 같은 후보를 점유하려 해도 하나의 claim token만 처리 권한을 얻는지 검증한다.
- `cowork-notification`이 FCM/SSE 처리 후 Kafka commit 전에 종료되어도 inbox와 수신자 ledger가 중복 생성되지 않는지 검증한다.
- 동일 event ID record를 여러 번 전달했을 때 SSE와 FCM payload의 event ID가 동일하고 완료된 fan-out이 새 논리 알림으로 생성되지 않는지 검증한다.
- 발행 후 crash와 일반 재시도를 반복해도 unread cache가 같은 메시지 때문에 중복 증가하지 않는지 검증한다.
- 서로 다른 메시지는 내용과 시각이 같아도 서로 다른 event ID로 처리되는지 검증한다.

## 완료 조건

- 하나의 채팅 메시지는 모든 재시도와 프로세스 재시작에서 동일한 알림 event ID를 사용한다.
- 동일 event ID의 Kafka record가 재전달되어도 downstream inbox와 수신자별 처리 상태가 중복 생성되지 않는다.
- millisecond 시각 충돌로 여러 poller가 같은 outbox 메시지를 동시에 처리하지 않는다.
- 같은 메시지의 재발행이 unread cache를 중복 증가시키지 않는다.
- SSE와 FCM payload에 안정적인 event ID가 포함되어 수신 측 중복 제거가 가능하다.
- 외부 전달의 at-least-once 경계와 FCM 개별 실패 처리의 별도 범위가 문서화되어 있다.
