CREATE TABLE tb_shared_accounts
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id           BIGINT       NOT NULL,
    provider             VARCHAR(30)  NOT NULL,
    provider_label       VARCHAR(100)          DEFAULT NULL,
    account_identifier   VARCHAR(255)          DEFAULT NULL,
    encrypted_credential TEXT                  DEFAULT NULL COMMENT 'AES-256-GCM',
    connected_via_oauth  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by           BIGINT       NOT NULL,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_shared_accounts_channel_id (channel_id),
    CONSTRAINT fk_tb_shared_accounts_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_credential_copy_histories
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    copied_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_credential_copy_histories_account_id (account_id, id),
    CONSTRAINT fk_tb_credential_copy_histories_account FOREIGN KEY (account_id) REFERENCES tb_shared_accounts (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
