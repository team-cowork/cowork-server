ALTER TABLE tb_teams
    ADD COLUMN created_by       BIGINT NULL AFTER created_at,
    ADD COLUMN last_modified_by BIGINT NULL AFTER updated_at;

ALTER TABLE tb_team_members
    ADD COLUMN created_by BIGINT NULL AFTER joined_at;
