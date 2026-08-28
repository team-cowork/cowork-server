ALTER TABLE tb_kafka_projection_checkpoints
    ADD COLUMN invalid_record_offset BIGINT NULL AFTER invalid_checkpoint_offset,
    ADD CONSTRAINT ck_tb_kafka_projection_checkpoints_invalid_record_offset
        CHECK (invalid_record_offset IS NULL OR invalid_record_offset >= 0);
