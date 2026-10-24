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

ALTER TABLE tb_project_access_projections
    ADD COLUMN can_read_private BOOLEAN NULL
    COMMENT '비공개 전환 후 읽기 가능 여부: cowork-preference.tb_project_access_states.can_read_private';

ALTER TABLE tb_projects ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tb_project_access_projections ADD COLUMN source_membership_version DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000';

ALTER TABLE tb_projects
    ADD COLUMN initial_read_membership_version DATETIME(6) NULL
    COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.state_occurred_at';

ALTER TABLE tb_team_memberships
    ADD COLUMN authorization_version DATETIME(6) NULL
    COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version';

ALTER TABLE tb_project_access_projections
    ADD COLUMN source_authorization_version DATETIME(6) NULL
    COMMENT '계산에 사용한 권한 버전: cowork-preference.tb_project_access_states.source_authorization_version';

ALTER TABLE tb_projects
    ADD COLUMN initial_read_authorization_version DATETIME(6) NULL
    COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.authorization_version';

-- Legacy seeds keep NULL: their original membership version remains the approval boundary.
-- Never backfill them from the creator's current membership or authorization version.
