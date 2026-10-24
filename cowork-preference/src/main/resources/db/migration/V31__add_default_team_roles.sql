ALTER TABLE tb_team_role_definitions ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN tb_team_role_definitions.is_default IS '모든 활성 팀 멤버에게 자동 적용되는 @everyone 역할';
ALTER TABLE tb_team_role_definitions DROP CONSTRAINT uq_tb_team_role_definitions_team_name;
ALTER TABLE tb_team_role_definitions ADD CONSTRAINT uq_tb_team_role_definitions_team_name_default UNIQUE (team_id, name, is_default);
CREATE UNIQUE INDEX uq_tb_team_role_definitions_team_default ON tb_team_role_definitions (team_id) WHERE is_default;
