CREATE TABLE tb_project_github_repos
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    project_id                BIGINT       NOT NULL COMMENT 'tb_projects.id',
    team_id                   BIGINT       NOT NULL COMMENT 'tb_projects.team_id 비정규화(유니크 제약용)',
    github_repo_url           VARCHAR(512) NOT NULL,
    github_webhook_channel_id BIGINT       DEFAULT NULL COMMENT 'cowork-channel의 tb_channels.id, GitHub 알림 수신 채널',
    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tb_project_github_repos_team_id_github_repo_url (team_id, github_repo_url),
    INDEX idx_tb_project_github_repos_project_id (project_id),
    INDEX idx_tb_project_github_repos_github_repo_url (github_repo_url)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
