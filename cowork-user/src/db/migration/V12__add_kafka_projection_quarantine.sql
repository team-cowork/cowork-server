CREATE TABLE tb_kafka_projection_quarantine
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    consumer_group VARCHAR(191) NOT NULL,
    topic_name     VARCHAR(191) NOT NULL,
    partition_id  INT          NOT NULL,
    record_offset BIGINT       NOT NULL,
    record_key     LONGBLOB     NULL,
    payload        LONGBLOB     NOT NULL,
    reason         VARCHAR(1000) NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_kafka_projection_quarantine_record
        UNIQUE (consumer_group, topic_name, partition_id, record_offset),
    INDEX idx_tb_kafka_projection_quarantine_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
