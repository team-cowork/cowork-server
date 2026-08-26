CREATE TABLE tb_kafka_outbox
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    topic      VARCHAR(255) NOT NULL,
    event_key  VARCHAR(255) NOT NULL,
    payload    JSON         NOT NULL,
    attempts   INT UNSIGNED NOT NULL DEFAULT 0,
    last_error TEXT         NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_kafka_outbox_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
