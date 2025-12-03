CREATE TABLE tb_device_tokens
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token      VARCHAR(512) NOT NULL,
    platform   VARCHAR(20)  NOT NULL COMMENT 'ANDROID, IOS, WEB',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_device_tokens_token (token),
    INDEX idx_tb_device_tokens_user_id (user_id),
    CONSTRAINT fk_tb_device_tokens_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
