ALTER TABLE tb_kafka_projection_offsets
    ADD COLUMN invalid_record_offset BIGINT NULL AFTER invalid_checkpoint_offset,
    ADD COLUMN last_snapshot_id CHAR(36) NULL AFTER snapshot_completed_offset,
    ADD COLUMN recovery_snapshot_id CHAR(36) NULL AFTER last_snapshot_id;
