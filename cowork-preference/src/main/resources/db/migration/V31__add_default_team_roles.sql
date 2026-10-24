ALTER TABLE tb_team_role_definitions ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN tb_team_role_definitions.is_default IS '모든 활성 팀 멤버에게 자동 적용되는 @everyone 역할';
ALTER TABLE tb_team_role_definitions DROP CONSTRAINT uq_tb_team_role_definitions_team_name;
ALTER TABLE tb_team_role_definitions ADD CONSTRAINT uq_tb_team_role_definitions_team_name_default UNIQUE (team_id, name, is_default);
CREATE UNIQUE INDEX uq_tb_team_role_definitions_team_default ON tb_team_role_definitions (team_id) WHERE is_default;

ALTER TABLE tb_project_access_states ADD COLUMN can_manage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tb_project_lifecycle_projections ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tb_project_access_states ADD COLUMN source_membership_version TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01 00:00:00+00';

ALTER TABLE account_project_role ADD COLUMN source_membership_version TIMESTAMPTZ;
UPDATE account_project_role a
SET source_membership_version = m.source_occurred_at
FROM tb_project_lifecycle_projections p, tb_team_member_projections m
WHERE p.project_id = a.project_id AND m.team_id = p.team_id AND m.account_id = a.account_id;
UPDATE account_project_role SET source_membership_version = '1970-01-01 00:00:00+00' WHERE source_membership_version IS NULL;
ALTER TABLE account_project_role ALTER COLUMN source_membership_version SET NOT NULL;
COMMENT ON COLUMN account_project_role.source_membership_version IS '역할 할당 시 확인한 cowork-team 멤버 상태 버전';

CREATE TABLE tb_initial_resource_access_ledger (
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('CHANNEL', 'PROJECT')),
    resource_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_type, resource_id)
);
COMMENT ON TABLE tb_initial_resource_access_ledger IS
    '초기 권한 처리 및 이후 수동 변경 기록. projection 재구성 때 삭제하지 않는 권한 원본 데이터';
COMMENT ON COLUMN tb_initial_resource_access_ledger.resource_id IS
    'CHANNEL: cowork-channel.tb_channels.id; PROJECT: cowork-project.tb_projects.id';

ALTER TABLE tb_team_member_projections ADD COLUMN authorization_version TIMESTAMPTZ;
COMMENT ON COLUMN tb_team_member_projections.authorization_version IS
    'cowork-team 멤버 권한 버전. NULL은 구형 이벤트이며 source_occurred_at으로 보수 판정';

ALTER TABLE tb_project_access_states ADD COLUMN source_authorization_version TIMESTAMPTZ;
COMMENT ON COLUMN tb_project_access_states.source_authorization_version IS
    '파생 프로젝트 권한 계산에 사용한 cowork-team 멤버의 원본 권한 버전';
