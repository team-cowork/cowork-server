CREATE TABLE tb_team_role_projections
(
    role_id            BIGINT      NOT NULL COMMENT 'cowork-preference의 team role id',
    team_id            BIGINT      NOT NULL COMMENT 'cowork-team의 tb_teams.id',
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
    team_id            BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    account_id         BIGINT       NOT NULL COMMENT 'cowork-user의 account id',
    role_id            BIGINT       NOT NULL COMMENT 'cowork-preference의 team role id',
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
    team_id            BIGINT      NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    account_id         BIGINT      NOT NULL COMMENT 'cowork-user의 account id',
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (projection_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_role_policy_projections
(
    policy_key         VARCHAR(120) NOT NULL,
    team_id            BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    channel_id         BIGINT       NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
    role_id            BIGINT       NOT NULL COMMENT 'cowork-preference의 team role id',
    message_read       BOOLEAN      NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (policy_key),
    INDEX idx_tb_channel_role_policy_projections_channel_role (channel_id, role_id),
    INDEX idx_tb_channel_role_policy_projections_team_channel (team_id, channel_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
