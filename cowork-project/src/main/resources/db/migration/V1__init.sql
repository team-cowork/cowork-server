CREATE TABLE tb_projects
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    team_id     BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED',
    created_by  BIGINT       NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_projects_team_id (team_id),
    INDEX idx_tb_projects_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    role       VARCHAR(20) NOT NULL DEFAULT 'VIEWER' COMMENT 'OWNER, EDITOR, VIEWER',
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_project_members (project_id, user_id),
    INDEX idx_tb_project_members_user_id (user_id),
    CONSTRAINT fk_tb_project_members_project FOREIGN KEY (project_id) REFERENCES tb_projects (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
