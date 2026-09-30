# 채팅 메시지 범위 감사와 정리

## 순서

1. 채널 범위 확정과 읽기 경로의 부모 채널 제한이 적용된 서버를 배포한다.
2. `cowork-chat`에서 `MONGODB_URI`를 설정하고 `npm run ops:message-scope-audit`를 실행한다. 출력은 JSON Lines이며 마지막 줄은 범주별 건수다. 메시지 본문과 첨부 URL은 출력하지 않는다. 보고서는 저장소 밖의 접근 제한된 위치에 보관한다.
3. 보고서의 메시지 ID를 운영 데이터와 대조한다. `CHANNEL_MISSING_OR_DELETED`와 `CHANNEL_SCOPE_INVALID`는 채널의 삭제·projection 복구 상태를 먼저 확인한다. 이 두 범주는 자동 정리 대상에 넣지 않는다.
4. `MESSAGE_SCOPE_MISMATCH`는 활성 채널 projection의 `teamId`·`projectId`로 정정할지 검토한다. `PARENT_INVALID_ID`, `PARENT_MISSING`, `PARENT_CROSS_CHANNEL`은 부모 참조를 `null`로 해제할지 검토한다. 정정 대상과 이전·새 값을 포함한 적용 목록을 승인받기 전에는 데이터를 변경하지 않는다.
5. 승인된 목록만 `_id`, `channelId`, 감사 당시 필드 값을 조건으로 한 갱신으로 적용한다. 조건이 달라진 문서는 건너뛰고 다시 감사한다. 변경 전 원본 문서와 적용 결과를 복구 가능한 운영 기록으로 보관한다.
6. 범위가 정정된 메시지가 검색 색인 대상이면 `npm run ops:message-search-index -- rebuild`로 기존 색인을 재구축한다. 완료 뒤 감사 명령과 색인 `status`를 다시 확인한다.

감사 명령은 읽기 전용이다. 실제 MongoDB 정리와 색인 재구축은 감사 보고서 검토와 승인 후 별도 운영 작업으로 진행한다.
