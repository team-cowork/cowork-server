CREATE TABLE tb_team_role_projections (
    role_id BIGINT NOT NULL COMMENT 'cowork-preference의 team role id',
    team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    name VARCHAR(100) NOT NULL,
    color_hex VARCHAR(7) NOT NULL,
    priority INT NOT NULL,
    mentionable BOOLEAN NOT NULL,
    permissions_json TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    source_created_at DATETIME(6) NOT NULL,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (role_id),
    INDEX idx_tb_team_role_projections_team_id (team_id)
);

CREATE TABLE tb_team_role_assignment_projections (
    projection_key VARCHAR(100) NOT NULL,
    team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    account_id BIGINT NOT NULL COMMENT 'cowork-user의 users.id',
    role_id BIGINT NOT NULL COMMENT 'cowork-preference의 team role id',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (projection_key),
    INDEX idx_tb_team_role_assignment_projections_team_account (team_id, account_id)
);

CREATE TABLE tb_team_role_member_tombstones (
    projection_key VARCHAR(80) NOT NULL,
    team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    account_id BIGINT NOT NULL COMMENT 'cowork-user의 users.id',
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (projection_key)
);
