ALTER TABLE tb_channels
    ADD COLUMN created_by       BIGINT NULL AFTER created_at,
    ADD COLUMN last_modified_by BIGINT NULL AFTER updated_at;
