-- 운영 DB에 적용되기 전인 V43~V46을 하나로 합친 마이그레이션입니다.

-- 1. 로드맵 관리 권한 manage_roadmaps
-- TODO(2026-11-24T00:00:00Z): Remove this one-time backfill when migration history is consolidated.
-- Roadmap management moves from `manage_team` to `manage_roadmaps`, so existing team roles keep their current access.
-- Managed owner and admin policies keep only `administrator`, which already allows every roadmap action.
-- The startup snapshot republishes every team role, and the newer updated_at makes consumers apply it.
-- Preserve applied Flyway checksums until that consolidation.
UPDATE tb_team_role_definitions
SET permissions = permissions || jsonb_build_object(
        'manage_roadmaps', COALESCE((permissions ->> 'manage_team')::boolean, false)
    ),
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE managed_policy IS NULL
  AND NOT permissions ? 'manage_roadmaps';

-- 2. 팀 권한 판정 결과
CREATE TABLE tb_team_access_states (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL,
    permissions jsonb NOT NULL,
    source_membership_version timestamp with time zone NOT NULL,
    source_authorization_version timestamp with time zone,
    state_occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT tb_team_access_states_pkey PRIMARY KEY (team_id, account_id)
);

COMMENT ON TABLE tb_team_access_states IS 'preference.team-access.state.v1로 발행하는 팀 권한 판정 결과. 탈퇴한 멤버와 삭제된 팀은 모든 권한이 false인 행으로 보존한다';

COMMENT ON COLUMN tb_team_access_states.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_access_states.account_id IS 'cowork-user.tb_accounts.id';

COMMENT ON COLUMN tb_team_access_states.permissions IS 'PermissionKeys.TEAM_SCOPED의 모든 키에 대한 판정 결과';

COMMENT ON COLUMN tb_team_access_states.source_membership_version IS '판정에 사용한 cowork-team 멤버 상태의 source mutation version. 활성 멤버가 아니면 1970-01-01';

COMMENT ON COLUMN tb_team_access_states.source_authorization_version IS '판정에 사용한 cowork-team 멤버의 원본 권한 버전';

-- 3. 팀 채널 권한 판정 결과
CREATE TABLE tb_channel_access_states (
    channel_id bigint NOT NULL,
    account_id bigint NOT NULL,
    team_id bigint NOT NULL,
    permissions jsonb NOT NULL,
    can_read_private boolean NOT NULL,
    source_membership_version timestamp with time zone NOT NULL,
    source_authorization_version timestamp with time zone,
    state_occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT tb_channel_access_states_pkey PRIMARY KEY (channel_id, account_id)
);

CREATE INDEX idx_tb_channel_access_states_team_id ON tb_channel_access_states (team_id, account_id);

COMMENT ON TABLE tb_channel_access_states IS 'preference.channel-access.state.v1로 발행하는 팀 채널 권한 판정 결과. 삭제된 채널, 탈퇴한 멤버, 삭제된 팀은 모든 권한이 false인 행으로 보존한다';

COMMENT ON COLUMN tb_channel_access_states.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_channel_access_states.account_id IS 'cowork-user.tb_accounts.id';

COMMENT ON COLUMN tb_channel_access_states.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_channel_access_states.permissions IS 'PermissionKeys.CHANNEL_SCOPED의 모든 키에 대한 판정 결과';

COMMENT ON COLUMN tb_channel_access_states.can_read_private IS '현재 역할로 채널이 비공개인 경우에도 접근할 수 있는지 여부';

COMMENT ON COLUMN tb_channel_access_states.source_membership_version IS '판정에 사용한 cowork-team 멤버 상태의 source mutation version. 활성 멤버가 아니면 1970-01-01';

COMMENT ON COLUMN tb_channel_access_states.source_authorization_version IS '판정에 사용한 cowork-team 멤버의 원본 권한 버전';

-- 4. 채널 수명주기 projection의 비공개 여부와 유형 필수화
-- 삭제된 채널은 접근 판정에 쓰이지 않으므로 채우는 값은 판정에 영향을 주지 않는다.
-- 삭제되지 않은 NULL 행은 채울 값을 알 수 없으므로 아래 NOT NULL 제약에서 마이그레이션을 실패시킨다.
UPDATE tb_channel_lifecycle_projections
SET is_private   = COALESCE(is_private, true),
    channel_type = COALESCE(channel_type, 'TEXT')
WHERE deleted
  AND (is_private IS NULL OR channel_type IS NULL);

ALTER TABLE tb_channel_lifecycle_projections
    ALTER COLUMN is_private SET NOT NULL,
    ALTER COLUMN channel_type SET NOT NULL;

COMMENT ON COLUMN tb_channel_lifecycle_projections.is_private IS 'cowork-channel의 tb_channels.is_private';

COMMENT ON COLUMN tb_channel_lifecycle_projections.channel_type IS 'cowork-channel의 tb_channels.type';
