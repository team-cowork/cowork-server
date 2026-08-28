CREATE TABLE tb_user_identity_operations
(
    operation_id    CHAR(36)     NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING | SUCCEEDED | FAILED',
    result_user_id  BIGINT       NULL COMMENT 'cowork-user가 commit한 tb_accounts.id',
    error_code      VARCHAR(64)  NULL,
    error_message   VARCHAR(500) NULL,
    result_hash     CHAR(64)     NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at    DATETIME(6)  NULL,
    PRIMARY KEY (operation_id),
    UNIQUE KEY uq_tb_user_identity_operations_idempotency_key (idempotency_key),
    INDEX idx_tb_user_identity_operations_status_updated_at (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
