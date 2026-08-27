CREATE TABLE tb_kafka_projection_offsets
(
    consumer_group VARCHAR(255) NOT NULL,
    topic_name   VARCHAR(255) NOT NULL,
    partition_id INT          NOT NULL,
    next_offset  BIGINT       NOT NULL,
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name, partition_id)
);
