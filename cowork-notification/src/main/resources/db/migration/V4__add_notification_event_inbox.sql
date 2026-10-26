-- 수신자 fan-out(FCM 원장 claim과 SSE 전달)을 끝낸 알림 트리거 이벤트. 설치별 FCM 결과는 tb_notification_delivery_retry가 맡는다.
CREATE TABLE tb_notification_event_inbox
(
    event_id     TEXT     NOT NULL PRIMARY KEY, -- 알림 트리거 이벤트의 안정적 식별자 (notification.trigger의 eventId)
    completed_at DATETIME NOT NULL
);
CREATE INDEX idx_tb_notification_event_inbox_completed_at ON tb_notification_event_inbox (completed_at);
