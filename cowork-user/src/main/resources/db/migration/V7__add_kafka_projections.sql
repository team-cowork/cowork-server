ALTER TABLE tb_accounts
    ADD COLUMN presence_updated_at DATETIME(6) NULL AFTER status_expires_at;

UPDATE tb_accounts
SET presence_updated_at = updated_at
WHERE presence_updated_at IS NULL;

ALTER TABLE tb_accounts
    MODIFY COLUMN presence_updated_at DATETIME(6) NOT NULL;

CREATE TABLE tb_team_member_projections
(
    team_id           BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    user_id           BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    role              VARCHAR(20)  NOT NULL COMMENT 'OWNER | ADMIN | MEMBER',
    team_name         VARCHAR(100) NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    event_occurred_at DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id, user_id),
    INDEX idx_tb_team_member_projections_user_id_active (user_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_user_presence_projections
(
    user_id           BIGINT      NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    status            VARCHAR(30) NOT NULL COMMENT 'online | offline',
    event_occurred_at DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
