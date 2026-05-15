CREATE TABLE tb_team_role_definitions
(
    id          BIGSERIAL PRIMARY KEY,
    team_id     BIGINT      NOT NULL,
    name        VARCHAR(50) NOT NULL,
    color_hex   VARCHAR(7)  NOT NULL DEFAULT '#99AAB5',
    priority    INTEGER     NOT NULL DEFAULT 0,
    mentionable BOOLEAN     NOT NULL DEFAULT FALSE,
    permissions JSONB       NOT NULL DEFAULT '[]',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tb_team_role_definitions_team_name UNIQUE (team_id, name)
);

COMMENT ON COLUMN tb_team_role_definitions.team_id IS 'cowork-team의 tb_teams.id';

CREATE INDEX idx_tb_team_role_definitions_team_priority
    ON tb_team_role_definitions (team_id, priority DESC, id ASC);

CREATE INDEX idx_tb_team_role_definitions_permissions
    ON tb_team_role_definitions USING GIN (permissions);

CREATE TABLE tb_account_team_roles
(
    account_id BIGINT NOT NULL,
    team_id    BIGINT NOT NULL,
    role_id    BIGINT NOT NULL,
    PRIMARY KEY (account_id, team_id, role_id)
);

COMMENT ON COLUMN tb_account_team_roles.account_id IS 'cowork-authorization의 사용자 ID';
COMMENT ON COLUMN tb_account_team_roles.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_account_team_roles.role_id IS 'cowork-preference의 tb_team_role_definitions.id';

CREATE INDEX idx_tb_account_team_roles_team_account
    ON tb_account_team_roles (team_id, account_id);

CREATE INDEX idx_tb_account_team_roles_role_id
    ON tb_account_team_roles (role_id);

CREATE TRIGGER trg_tb_team_role_definitions_updated_at
    BEFORE UPDATE ON tb_team_role_definitions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
