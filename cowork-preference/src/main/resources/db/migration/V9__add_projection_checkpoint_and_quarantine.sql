CREATE TABLE tb_projection_consumer_checkpoints
(
    consumer_group VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    partition_id   INTEGER      NOT NULL,
    next_offset    BIGINT       NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_projection_consumer_checkpoints
        PRIMARY KEY (consumer_group, topic, partition_id),
    CONSTRAINT ck_tb_projection_consumer_checkpoints_partition
        CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_projection_consumer_checkpoints_offset
        CHECK (next_offset >= 0)
);

CREATE TABLE tb_projection_quarantine
(
    id             BIGSERIAL    NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    topic          VARCHAR(255) NOT NULL,
    partition_id   INTEGER      NOT NULL,
    record_offset  BIGINT       NOT NULL,
    record_key     TEXT,
    payload        TEXT,
    reason         TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_projection_quarantine PRIMARY KEY (id),
    CONSTRAINT uq_tb_projection_quarantine_record
        UNIQUE (consumer_group, topic, partition_id, record_offset),
    CONSTRAINT ck_tb_projection_quarantine_partition
        CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_projection_quarantine_offset
        CHECK (record_offset >= 0)
);
