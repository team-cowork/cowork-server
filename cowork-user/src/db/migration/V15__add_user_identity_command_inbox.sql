CREATE TABLE tb_user_identity_command_inbox
(
    operation_id    CHAR(36)     NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    command_hash    CHAR(64)     NOT NULL,
    result_status   VARCHAR(20)  NOT NULL COMMENT 'SUCCEEDED | FAILED',
    result_payload  JSON         NOT NULL,
    result_hash     CHAR(64)     NOT NULL,
    processed_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (operation_id),
    UNIQUE KEY uq_tb_user_identity_command_inbox_idempotency_key (idempotency_key),
    INDEX idx_tb_user_identity_command_inbox_user_id_processed_at (user_id, processed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
