ALTER TABLE tb_projects
    ADD COLUMN position INT NOT NULL DEFAULT 0 COMMENT '팀 내 프로젝트 정렬 순서' AFTER status,
    ADD INDEX idx_tb_projects_team_position (team_id, position);
