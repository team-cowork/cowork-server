CREATE TABLE tb_teams
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    icon_url    VARCHAR(512),
    owner_id    BIGINT       NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_teams_owner_id (owner_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_members
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    team_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    role      VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER, ADMIN, MEMBER',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_team_members (team_id, user_id),
    INDEX idx_tb_team_members_user_id (user_id),
    CONSTRAINT fk_tb_team_members_team FOREIGN KEY (team_id) REFERENCES tb_teams (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
