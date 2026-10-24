ALTER TABLE tb_channel_event_states
    ADD COLUMN initial_role_policy_seed_json JSON NULL
        COMMENT '생성 시 요청한 역할 정책; 이후 상태 변경과 snapshot에서도 보존';
