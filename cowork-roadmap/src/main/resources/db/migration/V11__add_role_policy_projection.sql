CREATE TABLE tb_roadmap_role_policies (
    policy_key VARCHAR(128) PRIMARY KEY,
    team_id BIGINT NOT NULL COMMENT 'cowork-team tb_teams.id, 0 for account policy',
    kind VARCHAR(12) NOT NULL,
    role_id BIGINT COMMENT 'cowork-preference tb_team_role_definitions.id or tb_account_role_definitions.id',
    account_id BIGINT COMMENT 'cowork-user tb_accounts.id',
    is_default BOOLEAN NOT NULL,
    permissions JSON NOT NULL,
    deleted BOOLEAN NOT NULL,
    source_version DATETIME(6) NOT NULL,
    INDEX idx_tb_roadmap_role_policies_team_id (team_id, kind, role_id, account_id),
    INDEX idx_tb_roadmap_role_policies_account_id (team_id, kind, account_id, source_version)
);
