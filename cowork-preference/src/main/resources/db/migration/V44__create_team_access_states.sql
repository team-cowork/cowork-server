CREATE TABLE tb_team_access_states (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL,
    permissions jsonb NOT NULL,
    source_membership_version timestamp with time zone NOT NULL,
    source_authorization_version timestamp with time zone,
    state_occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT tb_team_access_states_pkey PRIMARY KEY (team_id, account_id)
);

COMMENT ON TABLE tb_team_access_states IS 'preference.team-access.state.v1로 발행하는 팀 권한 판정 결과. 탈퇴한 멤버와 삭제된 팀은 모든 권한이 false인 행으로 보존한다';

COMMENT ON COLUMN tb_team_access_states.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_access_states.account_id IS 'cowork-user.tb_accounts.id';

COMMENT ON COLUMN tb_team_access_states.permissions IS 'PermissionKeys.TEAM_SCOPED의 모든 키에 대한 판정 결과';

COMMENT ON COLUMN tb_team_access_states.source_membership_version IS '판정에 사용한 cowork-team 멤버 상태의 source mutation version. 활성 멤버가 아니면 1970-01-01';

COMMENT ON COLUMN tb_team_access_states.source_authorization_version IS '판정에 사용한 cowork-team 멤버의 원본 권한 버전';
