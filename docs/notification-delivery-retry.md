# FCM 개별 전송 실패의 선택적 재시도

`notification.trigger`를 발행하는 `cowork-chat`·`cowork-project`·`cowork-team`은 논리 알림마다 안정적인
`eventId`를 함께 보낸다. `internal/infra/fcm.Sender.Send`는 FCM 응답을 `SUCCESS`·`INVALID`·`RETRYABLE`·
`UNCLASSIFIED`로 분류하고, `(eventId, deviceTokenId)`를 canonical key로 쓰는
`tb_notification_delivery_retry`에 첫 시도 전에 적재한다. `RETRYABLE`/`UNCLASSIFIED`로 끝난 토큰은
`internal/domain/delivery.Worker`가 지수 백오프(최대 6회, 미분류는 2회) 뒤 재전송하며, 재시도 전에 토큰이
삭제·교체됐으면 `CANCELLED`로 종료한다.

- `eventId`가 없는(아직 갱신되지 않은) producer는 원장에 적재하지 않고 기존처럼 1회만 시도한다.
- 운영 지표: `cowork_notification_fcm_delivery_outcomes_total`(outcome·source별),
  `cowork_notification_fcm_delivery_quarantined_total`(error_class별),
  `cowork_notification_fcm_delivery_pending`(현재 대기 건수).
- 복구: `QUARANTINED`로 격리된 행은 `tb_notification_delivery_retry`에서 원인(`last_error_class`)을 확인한 뒤
  운영자가 직접 재전송 여부를 판단한다. 자동 재격리 해제는 없다.
