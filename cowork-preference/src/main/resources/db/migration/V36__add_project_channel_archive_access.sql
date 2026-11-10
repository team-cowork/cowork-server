ALTER TABLE tb_project_access_states
    ADD COLUMN can_channel_archive BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tb_project_access_states.can_channel_archive IS
    '프로젝트 채널 보관·복원 권한. 시작 snapshot이 모든 프로젝트를 다시 계산해 기존 행을 갱신한다';
