CREATE TABLE tb_kafka_projection_generations
(
    consumer_group    VARCHAR(255) NOT NULL,
    topic_name        VARCHAR(255) NOT NULL,
    replay_generation BIGINT       NOT NULL,
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, topic_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

ALTER TABLE tb_kafka_projection_offsets
    ADD COLUMN replay_generation BIGINT      NOT NULL DEFAULT 0 AFTER next_offset,
    ADD COLUMN replay_token      VARCHAR(36) NULL AFTER replay_generation,
    ADD COLUMN replay_owner      VARCHAR(36) NULL AFTER replay_token;

ALTER TABLE tb_kafka_projection_barriers
    ADD COLUMN replay_generation BIGINT      NOT NULL DEFAULT 0 AFTER marker_offset,
    ADD COLUMN replay_token      VARCHAR(36) NULL AFTER replay_generation;
