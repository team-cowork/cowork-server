ALTER TABLE tb_projects
    ADD COLUMN initial_read_membership_version DATETIME(6) NULL
    COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.state_occurred_at';
