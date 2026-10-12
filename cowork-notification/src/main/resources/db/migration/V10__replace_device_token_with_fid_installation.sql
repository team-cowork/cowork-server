-- FCM registration token은 Firebase installation ID(FID)로 변환할 수 없다.
-- 기존 token 등록과 그에 연결된 전송 상태는 이관하지 않고 정리하며, 클라이언트가 실제 FID를 새로 등록한다.
-- 각 문장은 중단 후 재실행해도 같은 결과를 만든다.
DROP TABLE IF EXISTS tb_notification_delivery_retry;

CREATE TABLE IF NOT EXISTS tb_device_installation (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    fid        VARCHAR(64) NOT NULL COMMENT 'Firebase installation ID',
    platform   VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_device_installation_fid (fid),
    INDEX idx_tb_device_installation_account_id (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tb_notification_delivery_retry
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    event_id         VARCHAR(64)  NOT NULL COMMENT '알림 트리거 이벤트의 안정적 식별자 (notification.trigger의 eventId)',
    installation_id  BIGINT       NOT NULL COMMENT 'tb_device_installation.id 스냅샷',
    fid              VARCHAR(64)  NOT NULL COMMENT '전송 시점의 FID 스냅샷',
    title            VARCHAR(255) NOT NULL,
    body             TEXT         NOT NULL,
    data_json        JSON         NULL,
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING_RETRY | IN_PROGRESS | SUCCESS | INVALID | QUARANTINED | CANCELLED',
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)  NULL,
    last_error_class VARCHAR(20)  NULL COMMENT 'RETRYABLE | UNCLASSIFIED',
    claim_token      VARCHAR(32)  NULL COMMENT 'IN_PROGRESS 동안만 값이 있음. 재시도/재개 시 새로 발급하며, 이 값이 일치하는 claim만 최종 상태를 기록할 수 있음(fencing)',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_notification_delivery_retry_event_installation (event_id, installation_id),
    INDEX idx_tb_notification_delivery_retry_status_next_attempt (status, next_attempt_at),
    INDEX idx_tb_notification_delivery_retry_status_updated_at (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS tb_device_token;
