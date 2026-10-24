CREATE TABLE tb_team_role_projections
(
    role_id            BIGINT      NOT NULL COMMENT 'cowork-preference.tb_team_role_definitions.id',
    team_id            BIGINT      NOT NULL COMMENT 'cowork-team.tb_teams.id',
    is_default         BOOLEAN NOT NULL DEFAULT FALSE,
    permissions_json   TEXT NULL,
    priority           INT         NOT NULL,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (role_id),
    INDEX idx_tb_team_role_projections_team_priority (team_id, priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_role_assignment_projections
(
    projection_key     VARCHAR(100) NOT NULL,
    team_id            BIGINT       NOT NULL COMMENT 'cowork-team.tb_teams.id',
    account_id         BIGINT       NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    role_id            BIGINT       NOT NULL COMMENT 'cowork-preference.tb_team_role_definitions.id',
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (projection_key),
    INDEX idx_tb_team_role_assignment_projections_team_account (team_id, account_id),
    INDEX idx_tb_team_role_assignment_projections_team_role (team_id, role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_team_role_member_tombstones
(
    projection_key     VARCHAR(80) NOT NULL,
    team_id            BIGINT      NOT NULL COMMENT 'cowork-team.tb_teams.id',
    account_id         BIGINT      NOT NULL COMMENT 'cowork-user.tb_accounts.id',
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (projection_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

ALTER TABLE tb_project_access_projections ADD COLUMN can_manage BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tb_projects ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tb_project_access_projections ADD COLUMN source_membership_version DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000';
