CREATE TABLE tb_kafka_outbox
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    topic      VARCHAR(249) NOT NULL,
    event_key  VARCHAR(512) NOT NULL,
    payload    LONGTEXT     NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempts   INT          NOT NULL DEFAULT 0,
    last_error LONGTEXT     NULL,
    PRIMARY KEY (id),
    INDEX idx_tb_kafka_outbox_id (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
