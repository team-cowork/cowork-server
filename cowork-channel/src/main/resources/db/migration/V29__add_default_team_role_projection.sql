ALTER TABLE tb_team_role_projections
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_tb_team_role_projections_team_default
    ON tb_team_role_projections (team_id, is_default, deleted);

ALTER TABLE tb_channel_event_states
    ADD COLUMN initial_role_policy_seed_json JSON NULL
        COMMENT '생성 시 요청한 역할 정책; 이후 상태 변경과 snapshot에서도 보존';

ALTER TABLE tb_team_memberships
    ADD COLUMN authorization_version DATETIME(6) NULL
        COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version; 구형 이벤트는 NULL';
