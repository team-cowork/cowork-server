ALTER TABLE tb_team_memberships
    ADD COLUMN authorization_version DATETIME(6) NULL
        COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version; 구형 이벤트는 NULL';
