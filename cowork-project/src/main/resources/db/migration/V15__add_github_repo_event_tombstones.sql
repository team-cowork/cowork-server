CREATE TABLE tb_project_github_repo_event_tombstones
(
    repo_id                   BIGINT       NOT NULL COMMENT 'deleted tb_project_github_repos.id',
    project_id                BIGINT       NOT NULL COMMENT 'deleted relation의 tb_projects.id',
    team_id                   BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    github_repo_url           VARCHAR(512) NOT NULL,
    owner                     VARCHAR(255) NOT NULL,
    repo                      VARCHAR(255) NOT NULL,
    github_webhook_channel_id BIGINT       NULL COMMENT 'cowork-channel의 tb_channels.id',
    state_occurred_at         DATETIME(6)  NOT NULL COMMENT 'latest project.github-repo.event DELETE version',
    CONSTRAINT pk_tb_project_github_repo_event_tombstones PRIMARY KEY (repo_id),
    INDEX idx_tb_project_github_repo_event_tombstones_project_id (project_id),
    INDEX idx_tb_project_github_repo_event_tombstones_team_id (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
