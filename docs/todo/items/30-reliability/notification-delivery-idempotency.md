# 채팅 알림 전달의 종단간 멱등성 보장

- **서비스**: cowork-chat, cowork-notification
- **우선순위**: 🟠 중간
- **현재 상태**: 안정적 `eventId`·무작위 claim은 구현되어 있으나 notification 전체 fan-out·SSE·unread의 재전달 경계가 남아 있다.

## 문제

chat은 메시지 ID를 `eventId`로 재사용하고 `notificationClaimId`로 worker 소유권을 구분한다.
notification의 FCM 선택적 재시도도 event·device 식별자를 사용한다. 식별자와 claim 자체를 새로
구현하는 과제는 남아 있지 않다.

그러나 Kafka record key는 없고 전체 수신자·SSE fan-out을 식별하는 inbox가 없다.
발행 뒤 상태 저장 전이나 전송 뒤 offset commit 전에 종료하면 재전달될 수 있다.
unread는 답장을 포함한 모든 메시지의 첫 시도에서 발행 전에 증가한다. 실패가 정상 처리되어 retry count가
저장된 경우에는 재증가를 피하지만, count 저장 전 프로세스가 종료되면 같은 메시지로 다시 증가한다.
SSE payload에는 `eventId`가 없다. FCM data에는 수신 계정 검증용 `accountId`가 있지만
`eventId`는 전달하지 않으므로, 안정적 이벤트 ID를 이용한 수신 측 중복 제거 계약은 남아 있다.

## 남은 경계

| 경계 | 목표 |
|---|---|
| Kafka 재발행 | 같은 논리 이벤트의 key·계약을 유지한다. key만으로 재발행 중복이 제거되지는 않는다. |
| 전체 fan-out | event inbox와 수신자별 상태로 완료한 논리 작업을 재생성하지 않는다. |
| unread | event로 중복 방지하거나 멱등한 cache 무효화를 사용한다. |
| 외부 FCM·SSE | 재전달 가능성을 명시하고 수신 측 중복 제거 식별자를 제공한다. |

## 코드 근거

- [Chat 알림 발행](../../../../cowork-chat/src/chat/kafka/notification-outbox.poller.ts#L205): unread 증가 → 발행 → SENT 저장 순서다. 최초 시도 중 crash하면 retry count가 없어 재증가할 수 있다.
- [Kafka record](../../../../cowork-chat/src/chat/kafka/notification-trigger.producer.ts#L100): payload eventId와 producer idempotence는 있으나 record key는 없다.
- [SSE fan-out](../../../../cowork-notification/internal/infra/kafka/consumer.go#L253): 이벤트 inbox 없이 broadcast하며 SSE payload에는 eventId가 없다. FCM 원장과 전체 fan-out 멱등성은 별개다.

## 할 일

- 기존 `eventId`와 claim을 유지하고 record key·envelope 버전의 전환 방식을 정한다.
- FCM의 기존 전송 원장과 겹치지 않게 event inbox·수신자별 fan-out·lease·보존·재처리를 설계한다.
- SSE·FCM payload의 안정적 ID와 클라이언트 중복 제거 계약을 맞춘다.
- unread 변경을 같은 메시지의 재발행에도 멱등하게 만든다.
- 외부 전달 성공 뒤 상태 저장 실패의 at-least-once 경계를 운영 절차에 기록한다.

## 검증

- mute·수신자 선택·계정 소유권 판단만 핵심 비즈니스 단위 테스트로 확인한다.
- claim·inbox·원장의 상태 전이와 outbox 호출은 정적으로 점검한다.
- 발행·전송·commit 사이 중단과 재전달, 여러 poller 경합, unread 수렴은 수동 운영 점검한다.

## 완료 조건

- 재전달이 같은 이벤트의 완료한 fan-out을 새 논리 알림으로 만들지 않는다.
- unread가 같은 메시지의 재발행으로 중복 증가하지 않는다.
- SSE·FCM의 수신 측 중복 제거와 외부 재전달 경계가 명시되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#353](https://github.com/team-cowork/cowork-server/pull/353) · [#378](https://github.com/team-cowork/cowork-server/pull/378) · [#379](https://github.com/team-cowork/cowork-server/pull/379) · [#447](https://github.com/team-cowork/cowork-server/pull/447) · [#448](https://github.com/team-cowork/cowork-server/pull/448).
- [대조 코드](../../../../cowork-notification/internal/infra/kafka/consumer.go): eventId·claim·FCM 원장은 구현되었다. Kafka key·SSE eventId·FCM eventId와 전체 fan-out inbox는 없고 unread 증가는 retry count 저장 전 중복될 수 있다.
- 판정: **부분 구현**. 전체 fan-out·unread 멱등성과 수신 측 eventId 계약을 남은 범위로 유지한다.
