ALTER TABLE tb_account_team_roles
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_tb_account_team_roles_team_account_role
    ON tb_account_team_roles (team_id, account_id, role_id);
