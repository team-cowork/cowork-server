ALTER TABLE tb_channels
    ADD COLUMN project_id BIGINT NULL COMMENT 'cowork-project의 tb_projects.id' AFTER team_id,
    ADD INDEX idx_tb_channels_project_id (project_id);
