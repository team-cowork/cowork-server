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
