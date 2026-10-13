# 상태 토픽 계약과 전환 경계

기존 데이터의 유지·복구·이관은 운영 결정이다. 현재 운영은 승인된 전체 초기화를 거쳐 새 데이터셋을 사용한다.
아래 제약은 기존 데이터를 보존하는 전환에 적용한다.

## 상태 토픽 계약

새 스트림의 소유자·소비자·key·보존 정책·snapshot 지원을 이 표에 등록한다.
전체 토픽 provisioning은 [`deploy/compose/stack.yaml`](../deploy/compose/stack.yaml)을 따른다.

| 토픽 | 소유자 | 소비자 | 데이터 key | 보존·복구 |
|---|---|---|---|---|
| `channel.event.v2` | channel | project, chat, preference | `<channelId>` | `compact`, 현재·삭제 상태 snapshot |
| `channel.member.event.v2` | channel | chat, voice | `<channelId>:<userId>` | `compact`, 현재·삭제 상태 snapshot |
| `project.event.v2` | project | channel, chat | `<projectId>` | `compact`, 현재·삭제 상태 snapshot |
| `project.member.event.v2` | project | chat | `<projectId>:<userId>` | `compact`, 현재·삭제 상태 snapshot |

`chat.message.v2`의 과거 전송 요청은 원본 상태 snapshot으로 복구할 수 없다.
미처리 요청을 보존하려면 구 소비자의 처리를 마친 뒤 전환한다.

## 기존 데이터 보존 시 제약

[Kafka projection 규칙](../.claude/rules/kafka-projections.md)의 유지보수 전환을 적용한다.
구·신 writer를 중지한 상태에서 소유자 데이터·삭제 이력과 미발행 outbox의 처리 범위를 확정한다.
이미 저장된 outbox의 topic만 새 이름으로 바꾸지 않는다.

Chat의 혼합 소유 collection은 수동 삭제하지 않는다. 관리자 rebuild로 채팅 소유 필드를 보존한다.
Readiness가 닫히면 Eureka를 통한 관리 요청도 도달하지 못할 수 있으므로 제한된 내부 접속을 사용한다.
Snapshot 주기는 복구 완료 시간의 상한이 아니다. upstream catch-up과 relay backlog가 추가된다.

구 토픽 폐기는 모든 새 소비자의 준비와 구 발행자·잔여 outbox 부재를 확인한 뒤 진행한다.
코드만 구버전으로 돌려도 새 토픽의 변경은 구 토픽에 생기지 않는다. rollback은 snapshot과 projection을
다시 맞추는 별도 전환이며, 구 토픽 폐기 후에는 새 계약을 복구한다.
