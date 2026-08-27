ALTER TABLE tb_projection_consumer_checkpoints
    ADD COLUMN invalid_record_offset BIGINT,
    ADD CONSTRAINT ck_tb_projection_consumer_checkpoints_invalid_record_offset
        CHECK (invalid_record_offset IS NULL OR invalid_record_offset >= 0);
