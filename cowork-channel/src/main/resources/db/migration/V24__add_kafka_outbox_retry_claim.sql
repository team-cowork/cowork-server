CREATE TABLE tb_kafka_outbox_fence
(
    id         BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_tb_kafka_outbox_fence_singleton CHECK (id = 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_kafka_outbox_fence (id) VALUES (1);

ALTER TABLE tb_kafka_outbox
    MODIFY COLUMN topic VARCHAR(249) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY COLUMN event_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD COLUMN is_barrier BOOLEAN NOT NULL DEFAULT FALSE AFTER payload,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER is_barrier,
    ADD COLUMN next_attempt_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER attempts,
    ADD COLUMN failure_type VARCHAR(32) NULL AFTER last_error,
    ADD COLUMN claim_owner VARCHAR(128) NULL AFTER failure_type,
    ADD COLUMN claim_until DATETIME(6) NULL AFTER claim_owner,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) AFTER claim_until,
    ADD CONSTRAINT ck_tb_kafka_outbox_status CHECK (status IN ('PENDING', 'QUARANTINED')),
    ADD CONSTRAINT ck_tb_kafka_outbox_attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_tb_kafka_outbox_schedule CHECK (
        (status = 'PENDING' AND next_attempt_at IS NOT NULL)
        OR (status = 'QUARANTINED' AND next_attempt_at IS NULL)
    ),
    ADD CONSTRAINT ck_tb_kafka_outbox_claim CHECK (
        (claim_owner IS NULL AND claim_until IS NULL)
        OR (claim_owner IS NOT NULL AND claim_until IS NOT NULL)
    ),
    ADD INDEX idx_tb_kafka_outbox_status_next_attempt (status, next_attempt_at, claim_until, id),
    ADD INDEX idx_tb_kafka_outbox_topic_event_key_id (topic, event_key, id);

UPDATE tb_kafka_outbox
SET is_barrier = TRUE
WHERE LEFT(event_key, 40) = '__cowork_projection_snapshot_complete__:';
