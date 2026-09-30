CREATE TABLE tb_channel_lifecycle_projections
(
    channel_id         BIGINT      NOT NULL,
    team_id            BIGINT,
    deleted            BOOLEAN     NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_channel_lifecycle_projections PRIMARY KEY (channel_id),
    CONSTRAINT ck_tb_channel_lifecycle_projections_channel_id CHECK (channel_id > 0),
    CONSTRAINT ck_tb_channel_lifecycle_projections_team_id CHECK (team_id IS NULL OR team_id > 0)
);

COMMENT ON TABLE tb_channel_lifecycle_projections IS
    'channel.event.v2 수명주기 projection. 삭제 상태는 채널 역할 정책 재생성을 막는 영구 fence로 보존한다';
COMMENT ON COLUMN tb_channel_lifecycle_projections.channel_id IS 'cowork-channel의 tb_channels.id';
COMMENT ON COLUMN tb_channel_lifecycle_projections.team_id IS 'cowork-team의 tb_teams.id, DM 채널은 NULL';
COMMENT ON COLUMN tb_channel_lifecycle_projections.source_occurred_at IS
    'cowork-channel 채널 상태의 source mutation version';
