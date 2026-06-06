CREATE TABLE tb_team_invites
(
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    team_id     BIGINT      NOT NULL,
    invite_code VARCHAR(8)  NOT NULL UNIQUE,
    created_by  BIGINT      NOT NULL,
    duration    VARCHAR(10) NOT NULL,
    expires_at  DATETIME(6) NULL,
    deleted_at  DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_tb_team_invites_team_id (team_id),
    INDEX idx_tb_team_invites_invite_code (invite_code)
);
