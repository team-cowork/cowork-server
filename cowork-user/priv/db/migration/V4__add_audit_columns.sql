ALTER TABLE tb_accounts
    ADD COLUMN created_by       BIGINT NULL AFTER created_at,
    ADD COLUMN last_modified_by BIGINT NULL AFTER updated_at;

ALTER TABLE tb_profiles
    ADD COLUMN created_by       BIGINT NULL AFTER created_at,
    ADD COLUMN last_modified_by BIGINT NULL AFTER updated_at;
