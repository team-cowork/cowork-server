CREATE TABLE tb_webhook_inbox
(
    event_id      VARBINARY(255) NOT NULL,
    event_type    VARCHAR(64)    NOT NULL,
    payload_hash  BINARY(32)     NOT NULL,
    message_count INT UNSIGNED  NOT NULL,
    occurred_at   DATETIME(6)    NOT NULL,
    accepted_at   DATETIME(6)    NOT NULL,
    expires_at    DATETIME(6)    NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_tb_webhook_inbox_expires_at (expires_at, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE tb_kafka_outbox ADD COLUMN source_event_id VARBINARY(255) NULL;
ALTER TABLE tb_kafka_outbox ADD COLUMN source_event_index BIGINT NULL;
ALTER TABLE tb_kafka_outbox ADD CONSTRAINT uq_tb_kafka_outbox_source_event
    UNIQUE (source_event_id, source_event_index);
ALTER TABLE tb_kafka_outbox ADD CONSTRAINT fk_tb_kafka_outbox_webhook_inbox
    FOREIGN KEY (source_event_id) REFERENCES tb_webhook_inbox (event_id) ON DELETE RESTRICT;
ALTER TABLE tb_kafka_outbox ADD CONSTRAINT ck_tb_kafka_outbox_source_event
    CHECK ((source_event_id IS NULL AND source_event_index IS NULL)
        OR (source_event_id IS NOT NULL AND source_event_index IS NOT NULL AND source_event_index >= 0));

DROP TABLE IF EXISTS tb_processed_events;
