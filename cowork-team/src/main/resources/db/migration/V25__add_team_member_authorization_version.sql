ALTER TABLE tb_team_member_event_states
    ADD COLUMN authorization_version DATETIME(6) NULL COMMENT '가입·탈퇴·기본 역할 변경 버전',
    ADD COLUMN authorization_state_version DATETIME(6) NULL COMMENT '권한 버전 계산이 적용된 원본 이벤트 버전';

UPDATE tb_team_member_event_states
SET authorization_version = state_occurred_at,
    authorization_state_version = state_occurred_at;
