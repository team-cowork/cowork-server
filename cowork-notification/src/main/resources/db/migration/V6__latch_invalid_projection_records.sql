ALTER TABLE tb_projection_checkpoints
    ADD COLUMN invalid_record_offset BIGINT NULL AFTER next_offset;

ALTER TABLE tb_projection_checkpoints
    ADD COLUMN last_snapshot_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER snapshot_completed_offset;

ALTER TABLE tb_projection_checkpoints
    ADD COLUMN recovery_snapshot_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER last_snapshot_id;

UPDATE tb_projection_checkpoints
SET last_snapshot_id = LOWER(snapshot_id)
WHERE snapshot_id IS NOT NULL
  AND last_snapshot_id IS NULL;
