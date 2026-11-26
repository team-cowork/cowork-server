CREATE TABLE tb_team_access_projections (
    team_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.team_id',
    account_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.account_id',
    allowed_permissions JSON NOT NULL,
    source_membership_version DATETIME(6) NOT NULL,
    source_authorization_version DATETIME(6),
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id, account_id)
);
