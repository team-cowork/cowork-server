ALTER TABLE tb_teams
    CHANGE COLUMN icon icon_url VARCHAR(512) NULL;

ALTER TABLE tb_teams
    ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0
        COMMENT 'cowork-authorization의 사용자 ID'
        AFTER id;

ALTER TABLE tb_teams
    DROP COLUMN visibility;

CREATE TABLE tb_team_members
(
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    team_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL COMMENT 'cowork-authorization의 사용자 ID',
    role      VARCHAR(20) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_team_members (team_id, user_id),
    INDEX idx_tb_team_members_team_id (team_id),
    INDEX idx_tb_team_members_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
