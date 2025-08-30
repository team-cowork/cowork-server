CREATE TABLE tb_teams
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500)          DEFAULT NULL,
    icon_key    VARCHAR(500)          DEFAULT NULL,
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC, PRIVATE',
    owner_id    BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_tb_teams_owner FOREIGN KEY (owner_id) REFERENCES tb_users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_members
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    team_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    role      VARCHAR(20) NOT NULL COMMENT 'OWNER, ADMIN, MEMBER',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_team_members_team_user (team_id, user_id),
    INDEX idx_tb_team_members_user_id (user_id),
    CONSTRAINT fk_tb_team_members_team FOREIGN KEY (team_id) REFERENCES tb_teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tb_team_members_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
