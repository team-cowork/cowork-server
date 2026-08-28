CREATE TABLE tb_project_projections
(
    project_id BIGINT NOT NULL COMMENT 'cowork-project의 tb_projects.id',
    team_id    BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (project_id),
    INDEX idx_tb_project_projections_team_id (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
