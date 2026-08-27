CREATE TABLE tb_kafka_projection_checkpoints
(
    consumer_group            VARCHAR(190) NOT NULL,
    topic_name                VARCHAR(190) NOT NULL,
    partition_id              INT          NOT NULL,
    topic_id                  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_offset               BIGINT       NOT NULL,
    invalid_checkpoint_offset BIGINT       NULL,
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_kafka_projection_barriers
(
    consumer_group VARCHAR(190) NOT NULL,
    topic_name     VARCHAR(190) NOT NULL,
    partition_id   INT          NOT NULL,
    topic_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_offset  BIGINT       NOT NULL,
    captured_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_kafka_projection_quarantine
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    consumer_group VARCHAR(190)  NOT NULL,
    topic_name     VARCHAR(190)  NOT NULL,
    partition_id   INT           NOT NULL,
    record_offset  BIGINT        NOT NULL,
    record_key     VARCHAR(512)  NULL,
    payload        MEDIUMTEXT    NULL,
    reason         VARCHAR(1000) NOT NULL,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_kafka_projection_quarantine_record
        UNIQUE (consumer_group, topic_name, partition_id, record_offset)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
