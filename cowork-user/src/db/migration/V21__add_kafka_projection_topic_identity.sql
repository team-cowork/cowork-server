ALTER TABLE tb_kafka_projection_generations
    ADD COLUMN topic_id VARCHAR(36) NULL COMMENT 'Kafka 브로커가 보고한 토픽 UUID' AFTER replay_generation;
