# JVM outbox 운영 제약

대상은 `cowork-channel`, `cowork-team`, `cowork-project`다.
상태·claim·barrier 규칙은 각 서비스의 `KafkaOutboxRelay`와 migration을 따른다.

- producer fence가 없는 구버전과 신버전 relay를 같은 DB에서 동시에 실행하지 않는다.
  구버전 replica를 모두 중지한 뒤 migration과 새 버전 배포를 진행한다.
- 공유 DB의 backlog는 replica별 합계가 아닌 최댓값으로 판단한다. 관측 실패 지표도 함께 확인한다.
- 격리는 같은 key의 후속 이벤트와 snapshot 완료를 막는다. 총량만 보고 영향 범위를 판단하지 않는다.
- 격리 복구는 같은 `(topic, event_key)`의 낮은 `id`부터 처리한다. 원인 해결 후 행을 잠근 transaction에서
  payload와 재시도·claim 상태를 함께 복구한다. 행 삭제로 순서를 우회하지 않는다.
- payload에는 업무 데이터가 포함된다. 접근 기록이 남는 제한된 DB 세션에서만 확인·수정한다.
