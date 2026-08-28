ALTER TABLE tb_account_team_roles
    ADD COLUMN source_membership_version TIMESTAMPTZ;

UPDATE tb_account_team_roles AS atr
SET source_membership_version = LEAST(atr.updated_at, member.source_occurred_at)
FROM tb_team_member_projections AS member
WHERE member.team_id = atr.team_id
  AND member.account_id = atr.account_id;

UPDATE tb_account_team_roles
SET source_membership_version = updated_at
WHERE source_membership_version IS NULL;

ALTER TABLE tb_account_team_roles
    ALTER COLUMN source_membership_version SET NOT NULL;

COMMENT ON COLUMN tb_account_team_roles.account_id IS 'cowork-user의 account id';
COMMENT ON COLUMN tb_account_team_roles.source_membership_version IS
    '역할 할당 시 검증한 cowork-team 멤버 상태 버전';
