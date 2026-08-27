ALTER TABLE tb_kafka_projection_checkpoints
    ADD COLUMN snapshot_completed_offset BIGINT NULL AFTER invalid_checkpoint_offset;

ALTER TABLE tb_kafka_outbox
    ADD COLUMN partition_id INT NULL AFTER topic,
    ADD CONSTRAINT ck_tb_kafka_outbox_partition CHECK (partition_id IS NULL OR partition_id >= 0);
