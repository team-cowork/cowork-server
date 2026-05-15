CREATE TABLE tb_channel_accounts
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    channel_id          BIGINT        NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
    provider            VARCHAR(50)   NOT NULL COMMENT 'AccountProvider enum 값',
    provider_label      VARCHAR(100)  NULL     COMMENT 'provider=CUSTOM일 때 사용자 정의 서비스 이름',
    account_identifier  VARCHAR(255)  NULL     COMMENT 'username 또는 email',
    credential          TEXT          NULL     COMMENT 'AES-256-GCM 암호화된 password 또는 token',
    connected_via_oauth TINYINT(1)    NOT NULL DEFAULT 0,
    created_by          BIGINT        NOT NULL COMMENT 'cowork-user의 tb_users.id',
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_channel_accounts_channel_id (channel_id),
    CONSTRAINT fk_tb_channel_accounts_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_account_credential_copies
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL COMMENT 'tb_channel_accounts.id',
    user_id    BIGINT      NOT NULL COMMENT 'cowork-user의 tb_users.id',
    copied_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_account_credential_copies_account_id (account_id),
    CONSTRAINT fk_tb_account_credential_copies_account FOREIGN KEY (account_id) REFERENCES tb_channel_accounts (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
