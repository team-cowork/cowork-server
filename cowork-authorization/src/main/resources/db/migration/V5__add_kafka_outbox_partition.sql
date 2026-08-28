ALTER TABLE tb_kafka_outbox
    ADD COLUMN partition_id INT NULL AFTER topic;
