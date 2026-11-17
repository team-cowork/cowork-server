CREATE TABLE tb_account_policy_projections (
    policy_key VARCHAR(100) PRIMARY KEY,
    record_type VARCHAR(16) NOT NULL,
    role_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_account_role_definitions.id',
    account_id BIGINT NULL COMMENT 'cowork-user의 account id',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    permissions_json TEXT NOT NULL,
    deleted BOOLEAN NOT NULL,
    source_occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_tb_account_policy_projections_account_id (account_id, role_id),
    INDEX idx_tb_account_policy_projections_role_id (role_id)
);
