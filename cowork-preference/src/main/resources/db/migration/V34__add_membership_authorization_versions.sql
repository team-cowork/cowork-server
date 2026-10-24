ALTER TABLE tb_team_member_projections ADD COLUMN authorization_version TIMESTAMPTZ;
COMMENT ON COLUMN tb_team_member_projections.authorization_version IS
    'cowork-team 멤버 권한 버전. NULL은 구형 이벤트이며 source_occurred_at으로 보수 판정';

ALTER TABLE tb_project_access_states ADD COLUMN source_authorization_version TIMESTAMPTZ;
COMMENT ON COLUMN tb_project_access_states.source_authorization_version IS
    '파생 프로젝트 권한 계산에 사용한 cowork-team 멤버의 원본 권한 버전';
