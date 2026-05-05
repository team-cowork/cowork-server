CREATE TABLE tb_team_memberships
(
    id      BIGINT      NOT NULL AUTO_INCREMENT,
    team_id BIGINT      NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    user_id BIGINT      NOT NULL COMMENT 'cowork-user의 tb_users.id',
    role    VARCHAR(20) NOT NULL COMMENT 'OWNER, ADMIN, MEMBER',
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_team_memberships_team_id_user_id UNIQUE (team_id, user_id),
    INDEX idx_tb_team_memberships_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
