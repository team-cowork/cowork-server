ALTER TABLE tb_projection_checkpoints
    ADD COLUMN snapshot_completed_offset BIGINT NULL AFTER next_offset;

ALTER TABLE tb_projection_checkpoints
    ADD COLUMN snapshot_id VARCHAR(36) NULL AFTER snapshot_completed_offset;

ALTER TABLE tb_projection_checkpoints
    ADD COLUMN snapshot_source VARCHAR(100) NULL AFTER snapshot_id;

ALTER TABLE tb_projection_checkpoints
    ADD COLUMN snapshot_occurred_at DATETIME(6) NULL AFTER snapshot_source;
