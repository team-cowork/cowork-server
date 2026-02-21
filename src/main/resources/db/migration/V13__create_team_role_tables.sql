CREATE TABLE tb_team_roles
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    team_id     BIGINT       NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    color_hex   CHAR(7)      NOT NULL DEFAULT '#99AAB5',
    priority    INT          NOT NULL DEFAULT 0,
    mentionable BOOLEAN      NOT NULL DEFAULT FALSE,
    permissions VARCHAR(500) NOT NULL DEFAULT '' COMMENT '쉼표로 구분한 TeamPermission 목록',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_team_roles_team_name (team_id, name),
    CONSTRAINT fk_tb_team_roles_team FOREIGN KEY (team_id) REFERENCES tb_teams (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_role_assignments
(
    id      BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_team_role_assignments_role_user (role_id, user_id),
    INDEX idx_tb_team_role_assignments_team_user (team_id, user_id),
    CONSTRAINT fk_tb_team_role_assignments_role FOREIGN KEY (role_id) REFERENCES tb_team_roles (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
