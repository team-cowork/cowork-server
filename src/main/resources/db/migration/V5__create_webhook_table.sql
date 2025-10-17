CREATE TABLE tb_webhooks
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(512)          DEFAULT NULL,
    token      VARCHAR(64)  NOT NULL,
    created_by BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_webhooks_channel_id (channel_id),
    CONSTRAINT fk_tb_webhooks_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
