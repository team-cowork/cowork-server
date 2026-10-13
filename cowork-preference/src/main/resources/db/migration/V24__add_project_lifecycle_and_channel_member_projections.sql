CREATE TABLE tb_project_lifecycle_projections
(
    project_id         BIGINT      NOT NULL,
    team_id            BIGINT      NOT NULL,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tb_project_lifecycle_projections PRIMARY KEY (project_id),
    CONSTRAINT ck_tb_project_lifecycle_projections_project_id CHECK (project_id > 0),
    CONSTRAINT ck_tb_project_lifecycle_projections_team_id CHECK (team_id > 0)
);

COMMENT ON TABLE tb_project_lifecycle_projections IS
    'project.event.v2 프로젝트 소속 팀과 삭제 상태 projection. 삭제 상태도 source version과 함께 보존한다';
COMMENT ON COLUMN tb_project_lifecycle_projections.project_id IS 'cowork-project의 tb_projects.id';
COMMENT ON COLUMN tb_project_lifecycle_projections.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_project_lifecycle_projections.source_occurred_at IS
    'cowork-project 프로젝트 상태의 source mutation version';

CREATE TABLE tb_channel_member_projections
(
    channel_id         BIGINT      NOT NULL,
    account_id         BIGINT      NOT NULL,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tb_channel_member_projections PRIMARY KEY (channel_id, account_id),
    CONSTRAINT ck_tb_channel_member_projections_channel_id CHECK (channel_id > 0),
    CONSTRAINT ck_tb_channel_member_projections_account_id CHECK (account_id > 0)
);

COMMENT ON TABLE tb_channel_member_projections IS
    'channel.member.event.v2 멤버십 projection. 삭제 상태도 source version과 함께 보존한다';
COMMENT ON COLUMN tb_channel_member_projections.channel_id IS 'cowork-channel의 tb_channels.id';
COMMENT ON COLUMN tb_channel_member_projections.account_id IS 'cowork-user의 account id';
COMMENT ON COLUMN tb_channel_member_projections.source_occurred_at IS
    'cowork-channel 채널 멤버 상태의 source mutation version';

ALTER TABLE tb_channel_lifecycle_projections
    ADD COLUMN is_private BOOLEAN;

COMMENT ON COLUMN tb_channel_lifecycle_projections.is_private IS
    'cowork-channel의 tb_channels.is_private. NULL은 channel.event.v2로 아직 확인하지 못한 값이며 공개로 가정하지 않는다';
