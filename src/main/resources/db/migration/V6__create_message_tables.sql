CREATE TABLE tb_messages
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id  BIGINT       NOT NULL,
    sender_id   BIGINT                DEFAULT NULL COMMENT '웹훅 메시지는 NULL',
    webhook_id  BIGINT                DEFAULT NULL,
    sender_name VARCHAR(100)          DEFAULT NULL COMMENT '웹훅 메시지 표시 이름',
    type        VARCHAR(20)  NOT NULL COMMENT 'TEXT, FILE, SYSTEM',
    content     TEXT         NOT NULL,
    edited      BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_messages_channel_id_id (channel_id, id),
    CONSTRAINT fk_tb_messages_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_message_attachments
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    message_id   BIGINT       NOT NULL,
    channel_id   BIGINT       NOT NULL,
    object_key   VARCHAR(500) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size    BIGINT       NOT NULL,
    uploaded_by  BIGINT       NOT NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_message_attachments_object_key (object_key),
    INDEX idx_tb_message_attachments_message_id (message_id),
    INDEX idx_tb_message_attachments_channel_id_id (channel_id, id),
    CONSTRAINT fk_tb_message_attachments_message FOREIGN KEY (message_id) REFERENCES tb_messages (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
