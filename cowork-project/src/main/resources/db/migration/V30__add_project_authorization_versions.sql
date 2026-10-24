ALTER TABLE tb_team_memberships
    ADD COLUMN authorization_version DATETIME(6) NULL
    COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version';

ALTER TABLE tb_project_access_projections
    ADD COLUMN source_authorization_version DATETIME(6) NULL
    COMMENT '계산에 사용한 권한 버전: cowork-preference.tb_project_access_states.source_authorization_version';

ALTER TABLE tb_projects
    ADD COLUMN initial_read_authorization_version DATETIME(6) NULL
    COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.authorization_version';

-- Legacy seeds keep NULL: their original membership version remains the approval boundary.
-- Never backfill them from the creator's current membership or authorization version.
