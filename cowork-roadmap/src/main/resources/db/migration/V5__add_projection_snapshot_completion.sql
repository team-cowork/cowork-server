DELETE FROM tb_kafka_projection_offsets;

ALTER TABLE tb_kafka_projection_offsets
    ADD COLUMN topic_id VARCHAR(64) NOT NULL AFTER partition_id,
    ADD COLUMN invalid_checkpoint_offset BIGINT NULL AFTER next_offset,
    ADD COLUMN snapshot_completed_offset BIGINT NULL AFTER invalid_checkpoint_offset;

CREATE TABLE tb_kafka_projection_barriers
(
    consumer_group VARCHAR(255) NOT NULL,
    topic_name    VARCHAR(255) NOT NULL,
    partition_id INT          NOT NULL,
    topic_id      VARCHAR(64)  NOT NULL,
    target_offset BIGINT       NOT NULL,
    captured_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id)
);

CREATE TABLE tb_kafka_projection_quarantine
(
    consumer_group VARCHAR(255)  NOT NULL,
    topic_name    VARCHAR(255)  NOT NULL,
    partition_id INT           NOT NULL,
    record_offset BIGINT        NOT NULL,
    record_key    VARCHAR(512)  NULL,
    payload       LONGTEXT      NULL,
    reason        VARCHAR(1000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id, record_offset)
);
