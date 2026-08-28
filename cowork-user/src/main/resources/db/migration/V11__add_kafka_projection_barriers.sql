CREATE TABLE tb_kafka_projection_barriers
(
    consumer_group VARCHAR(255) NOT NULL,
    topic_name     VARCHAR(255) NOT NULL,
    partition_id   INT          NOT NULL,
    marker_offset  BIGINT       NOT NULL,
    snapshot_id    VARCHAR(36)  NOT NULL,
    source_service VARCHAR(100) NOT NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    observed_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
