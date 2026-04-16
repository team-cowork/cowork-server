CREATE TABLE tb_projects
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    team_id    BIGINT       NOT NULL COMMENT '프로젝트 소속 팀 ID',
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_projects_team_id_id (team_id, id),
    INDEX idx_tb_projects_team_id_name (team_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_roles
(
    project_id BIGINT      NOT NULL,
    role       VARCHAR(50) NOT NULL,
    PRIMARY KEY (project_id, role),
    INDEX idx_tb_project_roles_role_project_id (role, project_id),
    CONSTRAINT fk_tb_project_roles_project FOREIGN KEY (project_id) REFERENCES tb_projects (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
