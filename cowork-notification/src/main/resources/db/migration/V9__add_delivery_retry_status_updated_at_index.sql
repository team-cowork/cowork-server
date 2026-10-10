-- 종료 상태 purge(status IN ? AND updated_at < ?)와 stale IN_PROGRESS 회수(status = ? AND updated_at < ?)용 인덱스
ALTER TABLE tb_notification_delivery_retry
    ADD INDEX idx_tb_notification_delivery_retry_status_updated_at (status, updated_at);
