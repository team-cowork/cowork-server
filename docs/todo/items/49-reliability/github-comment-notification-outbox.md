# GitHub 댓글 부가 알림의 outbox 전환

- **서비스**: cowork-project, cowork-notification
- **우선순위**: 🟡 낮음
- **현재 상태**: 완료. 댓글 부가 알림을 project의 transactional outbox에 기록하고 relay가 `notification.trigger`로 발행한다
- **결론**: 부모 작성자 조회 실패(GitHub 조회 실패, 프로필 매핑 누락·모호)는 재시도하지 않고 알림 생략으로 확정했다. 댓글 생성은 성공으로 응답하며 경고 로그만 남긴다

## 문제

`CreateGithubCommentServiceImpl`은 github-app에 댓글을 동기로 생성한 뒤, 부모 이슈·PR 작성자를 github-app에서 조회한다. 작성자가 댓글 작성자와 다르고 cowork 사용자로 유일하게 매핑되면 `GithubCommentNotificationPublisher`가 `KafkaTemplate.send`로 `notification.trigger`를 발행한다.

이 과정은 `runCatching` 안에서 실행된다. 부모 작성자 조회나 발행이 실패해도 댓글 생성은 성공으로 끝나고 알림은 영속 재시도 없이 사라진다. 다른 서비스로 가는 이벤트를 outbox 없이 보내는 경로이므로 서비스 간 상태 전달 패턴으로 복사하면 안 된다.

## 할 일

- 알림 대상이 결정된 뒤 알림 이벤트를 project의 transactional outbox에 기록한다.
- 부모 작성자 조회 실패를 재시도할지, 알림 생략으로 확정할지 정한다.
  - 알림 생략으로 확정했다. 알림은 댓글 생성의 부가 효과이며 GitHub 조회 재시도는 이 항목의 완료 조건(Kafka 발행 유실 방지) 밖이다.
- `GithubCommentNotificationPublisher`의 직접 발행을 제거한다.
- `CreateGithubCommentServiceImpl.execute`의 request transaction을 제거하고, GitHub 호출은 transaction 밖에서 수행한 뒤 outbox 기록만 짧은 쓰기 transaction으로 감싼다. publisher는 호출자 transaction에 참여한다.

## 검증

- 알림 대상 결정과 outbox 기록 조건의 단위 테스트를 작성한다.

## 완료 조건

- 댓글 부가 알림은 outbox를 거쳐 발행된다.
- Kafka 발행 실패로 알림이 유실되지 않는다.
