ALTER TABLE tb_team_member_event_states
    ADD COLUMN joined_at DATETIME(6) DEFAULT NULL COMMENT 'tb_team_members.joined_at. 탈퇴한 멤버는 NULL일 수 있다';

UPDATE tb_team_member_event_states s
    JOIN tb_team_members m ON m.team_id = s.team_id AND m.user_id = s.user_id
SET s.joined_at = m.joined_at;
