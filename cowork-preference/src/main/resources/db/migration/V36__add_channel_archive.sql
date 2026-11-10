ALTER TABLE tb_project_access_states
    ADD COLUMN can_channel_archive BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tb_project_access_states.can_channel_archive IS
    '프로젝트 채널 보관·복원 권한. 시작 snapshot이 모든 프로젝트를 다시 계산해 기존 행을 갱신한다';

ALTER TABLE tb_channel_lifecycle_projections
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tb_channel_lifecycle_projections.archived IS
    'channel.event.v2의 보관 상태. 보관된 채널은 설정과 웹훅 변경을 거부한다';
