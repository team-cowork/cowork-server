CREATE TABLE tb_notification_delivery_retry
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    event_id         VARCHAR(64)  NOT NULL COMMENT '알림 트리거 이벤트의 안정적 식별자 (notification.trigger의 eventId)',
    device_token_id  BIGINT       NOT NULL COMMENT 'tb_device_token.id 스냅샷',
    token            VARCHAR(512) NOT NULL COMMENT '전송 시점의 FCM 토큰 스냅샷',
    title            VARCHAR(255) NOT NULL,
    body             TEXT         NOT NULL,
    data_json        JSON         NULL,
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING_RETRY | IN_PROGRESS | SUCCESS | INVALID | QUARANTINED | CANCELLED',
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)  NULL,
    last_error_class VARCHAR(20)  NULL COMMENT 'RETRYABLE | UNCLASSIFIED',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_notification_delivery_retry_event_token (event_id, device_token_id),
    INDEX idx_tb_notification_delivery_retry_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
