ALTER TABLE tb_projection_consumer_checkpoints
    ADD COLUMN topic_id VARCHAR(64) NOT NULL,
    ADD COLUMN invalid_checkpoint_offset BIGINT,
    ADD COLUMN snapshot_completed_offset BIGINT;

CREATE TABLE tb_projection_consumer_barriers
(
    consumer_group VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    partition_id   INTEGER      NOT NULL,
    topic_id        VARCHAR(64)  NOT NULL,
    target_offset   BIGINT       NOT NULL,
    captured_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_projection_consumer_barriers
        PRIMARY KEY (consumer_group, topic, partition_id),
    CONSTRAINT ck_tb_projection_consumer_barriers_partition CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_projection_consumer_barriers_offset CHECK (target_offset >= 0)
);

ALTER TABLE tb_preference_event_outbox
    ADD COLUMN partition_id INTEGER,
    ADD CONSTRAINT ck_tb_preference_event_outbox_partition CHECK (partition_id IS NULL OR partition_id >= 0);
