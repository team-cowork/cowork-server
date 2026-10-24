ALTER TABLE tb_team_members
    ADD COLUMN nickname VARCHAR(50) NULL COMMENT '팀 안에서 표시할 닉네임; NULL이면 계정 닉네임을 사용' AFTER role;

ALTER TABLE tb_team_member_event_states
    ADD COLUMN nickname VARCHAR(50) NULL AFTER team_name;
