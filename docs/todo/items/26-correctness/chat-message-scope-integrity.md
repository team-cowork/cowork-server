# 채팅 메시지 채널·프로젝트·부모 범위 무결성 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 범위·부모 채널 검증과 감사 CLI, 요청 `teamId`·`projectId` 필드 제거, 중첩 답장 허용과 답장의 unread 포함은 구현되어 있으며 기존 데이터 정리와 클라이언트 계약 반영 확인이 남아 있다.

## 문제

현재 메시지 범위는 채널 projection에서 결정하고 다른 채널 부모는 발행 전에 거부한다.
이전 쓰기 경로가 만든 범위 불일치·부모 참조를 운영 데이터에서 감사하거나 정정하지는 않았다.

읽기 방어선만으로 기존 검색 색인과 잘못 저장된 범위가 복구되지는 않는다.
답장은 Discord식(채널 본문에 노출되는 인용 답장)으로 보고 답장의 답장도 허용한다. 답장도 일반 메시지처럼
unread를 올리며, Redis 카운터 증가와 MongoDB unread 집계 모두 답장을 제외하지 않는다.
`parentMessageId` 조회는 클라이언트 호환을 위해 직계 답장 목록으로 유지한다. 이 목록의 인용 대상은 조회한 부모
자신이므로 `mentionedMessage`를 채우지 않는다.

## 코드 근거

- [메시지 쓰기](../../../../cowork-chat/src/chat/chat.service.ts#L300): 채널에서 scope를 결정하며 부모는 동일 채널의 존재를 검증한다. 부모의 부모 여부는 거부하지 않는다.
- [unread 증가](../../../../cowork-chat/src/chat/kafka/notification-outbox.poller.ts#L217): 답장 여부와 무관하게 최초 처리에서 unread를 올린다.
- [unread 집계](../../../../cowork-chat/src/chat/repository/message.repository.ts#L752): 캐시 재계산도 답장을 포함해 센다.
- [스레드 조회](../../../../cowork-chat/src/chat/repository/message.repository.ts#L191): `parentMessageId`가 있으면 직계 답장만 반환한다.
- [데이터 감사](../../../../cowork-chat/src/ops/message-scope-audit.cli.ts#L26): 범위·부모 불일치를 읽기 전용으로 보고한다. 실제 정정·운영 색인 결과를 만들지는 않는다.

## 할 일

- 아래 절차로 기존 데이터의 범위·부모 참조를 감사하고 승인한 정정과 색인 재구축을 수행한다.
- ~~폐기 예정인 요청 `teamId`·`projectId`의 클라이언트 제거 시점을 정한다.~~
- ~~중첩 답장의 허용 범위를 확정한다.~~ (허용)
- ~~답장의 unread 제외 조건과 `parentMessageId` 스레드 조회 유지 여부를 Discord식 답장 계약에 맞춘다.~~ (unread 포함, 조회 유지)

### 데이터 감사·정리

1. 채널 범위 확정과 읽기 경로의 부모 채널 제한이 적용된 서버를 배포한다.
2. `cowork-chat`에서 `MONGODB_URI`를 설정하고 `npm run ops:message-scope-audit`를 실행한다.
   JSON Lines 보고서와 마지막 범주별 건수를 저장소 밖의 접근 제한된 위치에 보관한다.
3. `CHANNEL_MISSING_OR_DELETED`·`CHANNEL_SCOPE_INVALID`는 채널 삭제·projection 복구 상태를
   먼저 확인하며 자동 정리 대상에 넣지 않는다.
4. `MESSAGE_SCOPE_MISMATCH`는 활성 채널의 `teamId`·`projectId` 정정을,
   `PARENT_INVALID_ID`·`PARENT_MISSING`·`PARENT_CROSS_CHANNEL`은 부모 참조의 `null` 해제를 검토한다.
   메시지 ID와 이전·새 값을 포함한 적용 목록을 승인받는다.
5. 승인한 목록만 `_id`·`channelId`·감사 당시 필드 값으로 조건부 갱신한다.
   조건이 바뀐 문서는 건너뛰고 다시 감사한다. 원본과 적용 결과를 복구 가능한 운영 기록으로 보관한다.
6. 정정된 메시지가 색인 대상이면 `npm run ops:message-search-index -- rebuild`를 실행한다.
   완료 뒤 감사 명령과 색인 `status`를 다시 확인한다.

감사는 읽기 전용이다. 실제 데이터 변경과 재색인은 보고서 검토·승인 후 별도 운영 작업으로 수행한다.

## 검증

- 팀·프로젝트·DM 범위와 다른 채널 부모 거부, 답장인 부모에 대한 답장 허용은 서비스·validator 단위 테스트로 확인한다.
- 변경 전후 감사와 검색 색인 상태를 수동 확인한다.
- 읽기·알림의 부모 조회가 채널 조건을 유지하는지 정적으로 점검한다.

## 완료 조건

- 승인한 기존 범위 불일치와 부모 참조가 정리되고 검색 색인에 반영되어 있다.
- 채널 미존재·삭제·복구 중인 데이터를 잘못된 범위로 자동 정정하지 않는다.
- 요청 필드 제거와 중첩 답장 정책이 클라이언트·서버 계약에 반영되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#412](https://github.com/team-cowork/cowork-server/pull/412) · [#448](https://github.com/team-cowork/cowork-server/pull/448).
- [대조 코드](../../../../cowork-chat/src/chat/chat.service.ts): scope 검증·요청 필드 제거·중첩 답장·답장 unread는 구현되었다. 감사 CLI는 읽기 전용이며 정정과 재색인 성공 기록은 이번에 확인되지 않았다.
- 판정: **운영 정리**. 기존 데이터 감사·승인 정정·재색인과 클라이언트 계약 확인을 남은 범위로 유지한다.
