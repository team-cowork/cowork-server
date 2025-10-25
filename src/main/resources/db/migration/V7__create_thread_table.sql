CREATE TABLE tb_threads
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id        BIGINT       NOT NULL,
    name              VARCHAR(100) NOT NULL,
    parent_message_id BIGINT       NOT NULL,
    is_archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by        BIGINT       NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_threads_parent_message_id (parent_message_id),
    INDEX idx_tb_threads_channel_archived (channel_id, is_archived),
    CONSTRAINT fk_tb_threads_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

ALTER TABLE tb_messages
    ADD COLUMN thread_id BIGINT DEFAULT NULL AFTER channel_id,
    ADD INDEX idx_tb_messages_channel_thread_id (channel_id, thread_id, id);
