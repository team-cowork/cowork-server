CREATE TABLE tb_projects
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    team_id         BIGINT       NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500)          DEFAULT NULL,
    github_repo_url VARCHAR(512)          DEFAULT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED',
    position        INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_projects_team_position (team_id, position),
    CONSTRAINT fk_tb_projects_team FOREIGN KEY (team_id) REFERENCES tb_teams (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL COMMENT 'OWNER, EDITOR, VIEWER',
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_project_members_project_user (project_id, user_id),
    INDEX idx_tb_project_members_user_id (user_id),
    CONSTRAINT fk_tb_project_members_project FOREIGN KEY (project_id) REFERENCES tb_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_tb_project_members_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
