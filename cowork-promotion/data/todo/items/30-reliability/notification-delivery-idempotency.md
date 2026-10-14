# 채팅 알림 전달의 종단간 멱등성 보장

- **서비스**: cowork-chat, cowork-notification
- **우선순위**: 🟠 중간
- **현재 상태**: 전체 fan-out·unread 멱등성과 SSE·FCM 수신 측 `eventId` 계약이 남아 있다.

## 문제

발행·전송 뒤 상태 저장 전에 종료하면 같은 알림이 다시 전달될 수 있다.
unread도 첫 시도의 retry count 저장 전에 종료하면 같은 메시지로 다시 증가할 수 있다.
기존 `eventId`·worker claim·FCM 전송 원장만으로 전체 수신자·SSE fan-out의 중복을 막지는 못한다.

## 남은 경계

| 경계 | 목표 |
|---|---|
| Kafka 재발행 | 같은 이벤트의 key·계약을 유지한다. key 자체는 재발행 중복을 제거하지 않는다. |
| 전체 fan-out | event inbox와 수신자별 상태로 완료한 논리 작업을 재생성하지 않는다. |
| unread | event 중복 방지나 멱등한 cache 무효화를 사용한다. |
| 외부 FCM·SSE | 재전달 가능성을 명시하고 수신 측 중복 제거 식별자를 제공한다. |

## 할 일

- 기존 `eventId`·claim을 유지하고 record key·envelope 버전의 전환 방식을 정한다.
- 기존 FCM 원장과 겹치지 않게 event inbox·수신자별 fan-out·lease·보존·재처리를 설계한다.
- SSE·FCM payload의 안정적 ID와 클라이언트 중복 제거 계약을 맞춘다.
- unread 변경을 멱등하게 만들고 외부 성공 뒤 상태 저장 실패의 at-least-once 경계를 명시한다.

## 검증

- 변경한 mute·수신자 선택·계정 소유권 판단만 핵심 비즈니스 단위 테스트로 확인한다.
- claim·inbox·원장·outbox 상태 전이는 정적으로 확인한다.
- 발행·전송·commit 사이 중단, 여러 poller 경합과 unread 수렴은 수동 운영 점검한다.

## 완료 조건

- 재전달이 완료한 fan-out을 새 논리 알림으로 만들지 않고 unread를 중복 증가시키지 않는다.
- SSE·FCM 수신 측 중복 제거와 외부 재전달 경계가 명시되어 있다.
