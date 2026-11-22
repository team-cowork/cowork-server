-- V1~V41 마이그레이션을 하나로 합친 기준 스키마입니다 (PostgreSQL).

CREATE TYPE resource_type AS ENUM (
    'ACCOUNT',
    'TEAM',
    'PROJECT',
    'VOICE_CHANNEL',
    'TEXT_CHANNEL',
    'GITHUB_REPO'
);

CREATE FUNCTION set_channel_notification_state_occurred_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    next_version TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'INSERT' THEN
        next_version := clock_timestamp();
    ELSE
        next_version := GREATEST(clock_timestamp(), OLD.state_occurred_at + INTERVAL '1 microsecond');
    END IF;
    NEW.state_occurred_at := next_version;
    NEW.updated_at := next_version;
    RETURN NEW;
END;
$$;

CREATE FUNCTION set_resource_setting_state_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    next_version TIMESTAMPTZ;
BEGIN
    next_version = GREATEST(
        clock_timestamp(),
        COALESCE(NEW.state_occurred_at, '-infinity'::timestamptz)
    );
    IF TG_OP = 'UPDATE' THEN
        next_version = GREATEST(next_version, OLD.state_occurred_at + INTERVAL '1 microsecond');
    END IF;
    NEW.updated_at = next_version;
    NEW.state_occurred_at = next_version;
    RETURN NEW;
END;
$$;

CREATE FUNCTION set_team_role_updated_at_monotonic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := GREATEST(
        clock_timestamp(),
        OLD.updated_at + INTERVAL '1 microsecond'
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TABLE account_channel_notification (
    account_id bigint NOT NULL,
    channel_id bigint NOT NULL,
    settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    state_occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

COMMENT ON COLUMN account_channel_notification.state_occurred_at IS 'preference.channel-notification.changed authoritative monotonic version';

CREATE TABLE account_project_role (
    account_id bigint NOT NULL,
    project_id bigint NOT NULL,
    role_name character varying(50) NOT NULL,
    source_membership_version timestamp with time zone NOT NULL
);

COMMENT ON COLUMN account_project_role.source_membership_version IS '역할 할당 시 확인한 cowork-team 멤버 상태 버전';

CREATE TABLE project_role_definition (
    project_id bigint NOT NULL,
    role_name character varying(50) NOT NULL,
    permissions jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    priority integer DEFAULT 0 NOT NULL
);

CREATE TABLE resource_setting (
    resource_id bigint NOT NULL,
    resource_type resource_type NOT NULL,
    settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    state_occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

CREATE TABLE tb_account_policy_migrations (
    account_id bigint NOT NULL
);

COMMENT ON COLUMN tb_account_policy_migrations.account_id IS 'cowork-user의 account id; 기존 플랫폼 역할의 최초 정책 이관 기록';

CREATE TABLE tb_account_role_assignments (
    account_id bigint NOT NULL,
    role_id bigint NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    state_occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

COMMENT ON COLUMN tb_account_role_assignments.account_id IS 'cowork-user의 account id';

CREATE TABLE tb_account_role_definitions (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    permissions jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    bootstrap_policy boolean DEFAULT false NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    state_occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT ck_tb_account_role_definitions_permissions CHECK (((jsonb_typeof(permissions) = 'object'::text) AND (NOT jsonb_path_exists(permissions, '$.*?(@.type() != "boolean")'::jsonpath))))
);

CREATE SEQUENCE tb_account_role_definitions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_account_role_definitions_id_seq OWNED BY tb_account_role_definitions.id;

CREATE TABLE tb_account_team_roles (
    account_id bigint NOT NULL,
    team_id bigint NOT NULL,
    role_id bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_membership_version timestamp with time zone NOT NULL
);

COMMENT ON COLUMN tb_account_team_roles.account_id IS 'cowork-user의 account id';

COMMENT ON COLUMN tb_account_team_roles.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_account_team_roles.role_id IS 'cowork-preference의 tb_team_role_definitions.id';

COMMENT ON COLUMN tb_account_team_roles.source_membership_version IS '역할 할당 시 검증한 cowork-team 멤버 상태 버전';

CREATE TABLE tb_channel_lifecycle_projections (
    channel_id bigint NOT NULL,
    team_id bigint,
    deleted boolean NOT NULL,
    source_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    is_private boolean,
    channel_type character varying(30),
    archived boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_tb_channel_lifecycle_projections_channel_id CHECK ((channel_id > 0)),
    CONSTRAINT ck_tb_channel_lifecycle_projections_team_id CHECK (((team_id IS NULL) OR (team_id > 0)))
);

COMMENT ON TABLE tb_channel_lifecycle_projections IS 'channel.event.v2 수명주기 projection. 삭제 상태는 채널 역할 정책 재생성을 막는 영구 fence로 보존한다';

COMMENT ON COLUMN tb_channel_lifecycle_projections.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_channel_lifecycle_projections.team_id IS 'cowork-team의 tb_teams.id, DM 채널은 NULL';

COMMENT ON COLUMN tb_channel_lifecycle_projections.source_occurred_at IS 'cowork-channel 채널 상태의 source mutation version';

COMMENT ON COLUMN tb_channel_lifecycle_projections.is_private IS 'cowork-channel의 tb_channels.is_private. NULL은 channel.event.v2로 아직 확인하지 못한 값이며 공개로 가정하지 않는다';

COMMENT ON COLUMN tb_channel_lifecycle_projections.archived IS 'channel.event.v2의 보관 상태. 보관된 채널은 설정과 웹훅 변경을 거부한다';

CREATE TABLE tb_channel_role_policies (
    team_id bigint NOT NULL,
    channel_id bigint NOT NULL,
    role_id bigint NOT NULL,
    permissions jsonb NOT NULL,
    state_occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_channel_role_policies_permissions CHECK (((jsonb_typeof(permissions) = 'object'::text) AND (permissions <> '{}'::jsonb) AND (NOT jsonb_path_exists(permissions, '$.*?(@.type() != "boolean")'::jsonpath))))
);

COMMENT ON TABLE tb_channel_role_policies IS '채널별 사용자 정의 팀 역할 권한의 authoritative full state';

COMMENT ON COLUMN tb_channel_role_policies.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_channel_role_policies.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_channel_role_policies.role_id IS 'cowork-preference의 tb_team_role_definitions.id';

COMMENT ON COLUMN tb_channel_role_policies.permissions IS '채널별 역할 권한 덮어쓰기. true는 허용, false는 거부이며 누락된 키는 역할 기본 권한을 따른다';

CREATE TABLE tb_channel_role_policy_command_inbox (
    operation_id character(36) NOT NULL,
    actor_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_hash character(64) NOT NULL,
    command_type character varying(10) NOT NULL,
    team_id bigint NOT NULL,
    channel_id bigint NOT NULL,
    role_id bigint NOT NULL,
    actor_membership_version text CONSTRAINT tb_channel_role_policy_comman_actor_membership_version_not_null NOT NULL,
    permissions jsonb,
    result jsonb NOT NULL,
    processed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_channel_role_policy_command_inbox_shape CHECK (((((command_type)::text = 'UPSERT'::text) AND (permissions IS NOT NULL) AND (jsonb_typeof(permissions) = 'object'::text) AND (permissions <> '{}'::jsonb) AND (NOT jsonb_path_exists(permissions, '$.*?(@.type() != "boolean")'::jsonpath))) OR (((command_type)::text = 'DELETE'::text) AND (permissions IS NULL)))),
    CONSTRAINT ck_tb_channel_role_policy_command_inbox_type CHECK (command_type IN ('UPSERT', 'DELETE'))
);

COMMENT ON COLUMN tb_channel_role_policy_command_inbox.actor_id IS 'cowork-user의 account id';

COMMENT ON COLUMN tb_channel_role_policy_command_inbox.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_channel_role_policy_command_inbox.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_channel_role_policy_command_inbox.role_id IS 'command 처리 시 검증한 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_channel_role_policy_command_quarantine (
    id bigint NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint CONSTRAINT tb_channel_role_policy_command_quarantin_record_offset_not_null NOT NULL,
    record_key text,
    payload text,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_channel_role_policy_command_quarantine_offset CHECK ((record_offset >= 0)),
    CONSTRAINT ck_tb_channel_role_policy_command_quarantine_partition CHECK ((partition_id >= 0))
);

CREATE SEQUENCE tb_channel_role_policy_command_quarantine_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_channel_role_policy_command_quarantine_id_seq OWNED BY tb_channel_role_policy_command_quarantine.id;

CREATE TABLE tb_channel_role_policy_tombstones (
    team_id bigint NOT NULL,
    channel_id bigint NOT NULL,
    role_id bigint NOT NULL,
    state_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE tb_channel_role_policy_tombstones IS 'compacted state topic에서 삭제 상태를 full snapshot으로 재발행하기 위한 영구 tombstone';

COMMENT ON COLUMN tb_channel_role_policy_tombstones.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_channel_role_policy_tombstones.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_channel_role_policy_tombstones.role_id IS '삭제될 수 있는 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_dm_participant_projections (
    channel_id bigint CONSTRAINT tb_channel_member_projections_channel_id_not_null NOT NULL,
    account_id bigint CONSTRAINT tb_channel_member_projections_account_id_not_null NOT NULL,
    deleted boolean DEFAULT false CONSTRAINT tb_channel_member_projections_deleted_not_null NOT NULL,
    source_occurred_at timestamp with time zone CONSTRAINT tb_channel_member_projections_source_occurred_at_not_null NOT NULL,
    CONSTRAINT ck_tb_dm_participant_projections_account_id CHECK ((account_id > 0)),
    CONSTRAINT ck_tb_dm_participant_projections_channel_id CHECK ((channel_id > 0))
);

COMMENT ON TABLE tb_dm_participant_projections IS 'channel.dm-participant.state.v1 DM 참여 상태. 삭제 상태도 source version과 함께 보존한다';

COMMENT ON COLUMN tb_dm_participant_projections.channel_id IS 'cowork-channel의 tb_channels.id';

COMMENT ON COLUMN tb_dm_participant_projections.account_id IS 'cowork-user.tb_accounts.id';

COMMENT ON COLUMN tb_dm_participant_projections.source_occurred_at IS 'cowork-channel DM 참여 상태의 source mutation version';

CREATE TABLE tb_github_repo_setting_command_inbox (
    operation_id character(36) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    command_type character varying(10) NOT NULL,
    repo_id bigint NOT NULL,
    requested_by bigint,
    label_auto_apply boolean,
    command_occurred_at text CONSTRAINT tb_github_repo_setting_command_inb_command_occurred_at_not_null NOT NULL,
    result jsonb,
    processed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_github_repo_setting_command_inbox_shape CHECK (((((command_type)::text = 'UPDATE'::text) AND (requested_by IS NOT NULL) AND (label_auto_apply IS NOT NULL) AND (result IS NOT NULL)) OR (((command_type)::text = 'DELETE'::text) AND (label_auto_apply IS NULL) AND (result IS NULL)))),
    CONSTRAINT ck_tb_github_repo_setting_command_inbox_type CHECK (command_type IN ('UPDATE', 'DELETE'))
);

COMMENT ON COLUMN tb_github_repo_setting_command_inbox.repo_id IS 'cowork-project의 GitHub repository id';

COMMENT ON COLUMN tb_github_repo_setting_command_inbox.requested_by IS 'cowork-user의 account id';

CREATE TABLE tb_github_repo_setting_command_quarantine (
    id bigint NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint CONSTRAINT tb_github_repo_setting_command_quarantin_record_offset_not_null NOT NULL,
    record_key text,
    payload text,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_github_repo_setting_command_quarantine_offset CHECK ((record_offset >= 0)),
    CONSTRAINT ck_tb_github_repo_setting_command_quarantine_partition CHECK ((partition_id >= 0))
);

CREATE SEQUENCE tb_github_repo_setting_command_quarantine_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_github_repo_setting_command_quarantine_id_seq OWNED BY tb_github_repo_setting_command_quarantine.id;

CREATE TABLE tb_github_repo_setting_tombstones (
    repo_id bigint NOT NULL,
    source_occurred_at timestamp with time zone NOT NULL
);

COMMENT ON COLUMN tb_github_repo_setting_tombstones.repo_id IS 'cowork-project의 GitHub repository id';

CREATE TABLE tb_initial_resource_access_ledger (
    resource_type character varying(16) NOT NULL,
    resource_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tb_initial_resource_access_ledger_resource_type_check CHECK (resource_type IN ('CHANNEL', 'PROJECT'))
);

COMMENT ON TABLE tb_initial_resource_access_ledger IS '초기 권한 처리 및 이후 수동 변경 기록. projection 재구성 때 삭제하지 않는 권한 원본 데이터';

COMMENT ON COLUMN tb_initial_resource_access_ledger.resource_id IS 'CHANNEL: cowork-channel.tb_channels.id; PROJECT: cowork-project.tb_projects.id';

CREATE TABLE tb_preference_event_outbox (
    id bigint NOT NULL,
    topic character varying(255) NOT NULL,
    record_key text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    partition_id integer,
    CONSTRAINT ck_tb_preference_event_outbox_partition CHECK (((partition_id IS NULL) OR (partition_id >= 0))),
    CONSTRAINT ck_tb_preference_event_outbox_record_key CHECK ((length(record_key) > 0)),
    CONSTRAINT ck_tb_preference_event_outbox_topic_not_blank CHECK (((topic)::text ~ '[^[:space:]]'::text))
);

CREATE SEQUENCE tb_preference_event_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_preference_event_outbox_id_seq OWNED BY tb_preference_event_outbox.id;

CREATE TABLE tb_project_access_states (
    project_id bigint NOT NULL,
    account_id bigint NOT NULL,
    can_read boolean NOT NULL,
    can_write boolean NOT NULL,
    can_reorder boolean DEFAULT false NOT NULL,
    state_occurred_at timestamp with time zone NOT NULL,
    can_manage boolean DEFAULT false NOT NULL,
    can_read_private boolean DEFAULT false NOT NULL,
    source_membership_version timestamp with time zone DEFAULT '1970-01-01 00:00:00+00'::timestamp with time zone NOT NULL,
    source_authorization_version timestamp with time zone,
    can_channel_archive boolean DEFAULT false NOT NULL,
    can_board_read boolean DEFAULT false NOT NULL,
    can_board_write boolean DEFAULT false NOT NULL
);

COMMENT ON COLUMN tb_project_access_states.project_id IS 'cowork-project.tb_projects.id';

COMMENT ON COLUMN tb_project_access_states.account_id IS 'cowork-user.tb_accounts.id';

COMMENT ON COLUMN tb_project_access_states.can_read_private IS '현재 역할로 프로젝트가 비공개인 경우에도 읽을 수 있는지 여부';

COMMENT ON COLUMN tb_project_access_states.source_authorization_version IS '파생 프로젝트 권한 계산에 사용한 cowork-team 멤버의 원본 권한 버전';

COMMENT ON COLUMN tb_project_access_states.can_channel_archive IS '프로젝트 채널 보관·복원 권한. 시작 snapshot이 모든 프로젝트를 다시 계산해 기존 행을 갱신한다';

COMMENT ON COLUMN tb_project_access_states.can_board_read IS '프로젝트에 연결된 GitHub Project 보드 보기 권한. 시작 snapshot이 모든 프로젝트를 다시 계산해 기존 행을 갱신한다';

COMMENT ON COLUMN tb_project_access_states.can_board_write IS '프로젝트에 연결된 GitHub Project 보드의 카드 이동 권한. 보드 보기 권한이 있을 때만 참이다';

CREATE TABLE tb_project_lifecycle_projections (
    project_id bigint NOT NULL,
    team_id bigint NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    source_occurred_at timestamp with time zone NOT NULL,
    created_by bigint,
    is_private boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_tb_project_lifecycle_projections_project_id CHECK ((project_id > 0)),
    CONSTRAINT ck_tb_project_lifecycle_projections_team_id CHECK ((team_id > 0))
);

COMMENT ON TABLE tb_project_lifecycle_projections IS 'project.event.v2 프로젝트 소속 팀과 삭제 상태 projection. 삭제 상태도 source version과 함께 보존한다';

COMMENT ON COLUMN tb_project_lifecycle_projections.project_id IS 'cowork-project의 tb_projects.id';

COMMENT ON COLUMN tb_project_lifecycle_projections.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_project_lifecycle_projections.source_occurred_at IS 'cowork-project 프로젝트 상태의 source mutation version';

COMMENT ON COLUMN tb_project_lifecycle_projections.created_by IS 'cowork-user.tb_accounts.id';

CREATE TABLE tb_projection_consumer_barriers (
    consumer_group character varying(255) NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    topic_id character varying(64) NOT NULL,
    target_offset bigint NOT NULL,
    captured_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_projection_consumer_barriers_offset CHECK ((target_offset >= 0)),
    CONSTRAINT ck_tb_projection_consumer_barriers_partition CHECK ((partition_id >= 0))
);

CREATE TABLE tb_projection_consumer_checkpoints (
    consumer_group character varying(255) NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    next_offset bigint NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    topic_id character varying(64) NOT NULL,
    invalid_checkpoint_offset bigint,
    snapshot_completed_offset bigint,
    invalid_record_offset bigint,
    CONSTRAINT ck_tb_projection_consumer_checkpoints_invalid_record_offset CHECK (((invalid_record_offset IS NULL) OR (invalid_record_offset >= 0))),
    CONSTRAINT ck_tb_projection_consumer_checkpoints_offset CHECK ((next_offset >= 0)),
    CONSTRAINT ck_tb_projection_consumer_checkpoints_partition CHECK ((partition_id >= 0))
);

CREATE TABLE tb_projection_quarantine (
    id bigint NOT NULL,
    consumer_group character varying(255) NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    record_key text,
    payload text,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_projection_quarantine_offset CHECK ((record_offset >= 0)),
    CONSTRAINT ck_tb_projection_quarantine_partition CHECK ((partition_id >= 0))
);

CREATE SEQUENCE tb_projection_quarantine_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_projection_quarantine_id_seq OWNED BY tb_projection_quarantine.id;

CREATE TABLE tb_team_member_projections (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL,
    built_in_role character varying(20) NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    source_occurred_at timestamp with time zone NOT NULL,
    authorization_version timestamp with time zone,
    CONSTRAINT ck_tb_team_member_projections_role CHECK (built_in_role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

COMMENT ON COLUMN tb_team_member_projections.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_member_projections.account_id IS 'cowork-user의 account id';

COMMENT ON COLUMN tb_team_member_projections.authorization_version IS 'cowork-team 멤버 권한 버전. NULL은 구형 이벤트이며 source_occurred_at으로 보수 판정';

CREATE TABLE tb_team_policy_migrations (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL
);

COMMENT ON COLUMN tb_team_policy_migrations.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_policy_migrations.account_id IS 'cowork-user의 account id';

CREATE TABLE tb_team_role_assignment_tombstones (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL,
    role_id bigint NOT NULL,
    state_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE tb_team_role_assignment_tombstones IS 'preference.team-role.changed full snapshot에서 ASSIGNMENT_DELETED를 재발행하기 위한 영구 tombstone';

COMMENT ON COLUMN tb_team_role_assignment_tombstones.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_role_assignment_tombstones.account_id IS 'cowork-user의 account id';

COMMENT ON COLUMN tb_team_role_assignment_tombstones.role_id IS '삭제될 수 있는 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_team_role_command_inbox (
    operation_id character(36) NOT NULL,
    actor_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_hash character(64) NOT NULL,
    command_type character varying(20) NOT NULL,
    team_id bigint NOT NULL,
    result jsonb NOT NULL,
    processed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_team_role_command_inbox_type CHECK (command_type IN ('CREATE', 'UPDATE', 'DELETE', 'ASSIGN', 'REVOKE'))
);

COMMENT ON COLUMN tb_team_role_command_inbox.actor_id IS 'cowork-user의 account id';

COMMENT ON COLUMN tb_team_role_command_inbox.team_id IS 'cowork-team의 tb_teams.id';

CREATE TABLE tb_team_role_command_quarantine (
    id bigint NOT NULL,
    topic character varying(255) NOT NULL,
    partition_id integer NOT NULL,
    record_offset bigint NOT NULL,
    record_key text,
    payload text,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_tb_team_role_command_quarantine_offset CHECK ((record_offset >= 0)),
    CONSTRAINT ck_tb_team_role_command_quarantine_partition CHECK ((partition_id >= 0))
);

CREATE SEQUENCE tb_team_role_command_quarantine_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_team_role_command_quarantine_id_seq OWNED BY tb_team_role_command_quarantine.id;

CREATE TABLE tb_team_role_definitions (
    id bigint NOT NULL,
    team_id bigint NOT NULL,
    name character varying(50) NOT NULL,
    color_hex character varying(7) DEFAULT '#99AAB5'::character varying NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    mentionable boolean DEFAULT false NOT NULL,
    permissions jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    managed_policy character varying(16),
    CONSTRAINT ck_tb_team_role_definitions_managed_policy CHECK (managed_policy IN ('owner', 'admin')),
    CONSTRAINT ck_tb_team_role_definitions_permissions CHECK (((jsonb_typeof(permissions) = 'object'::text) AND (NOT jsonb_path_exists(permissions, '$.*?(@.type() != "boolean")'::jsonpath))))
);

COMMENT ON COLUMN tb_team_role_definitions.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_role_definitions.permissions IS '역할 기본 권한. 권한 키별 boolean 전체 상태이며 누락된 키는 false';

COMMENT ON COLUMN tb_team_role_definitions.is_default IS '모든 활성 팀 멤버에게 자동 적용되는 @everyone 역할';

CREATE SEQUENCE tb_team_role_definitions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE tb_team_role_definitions_id_seq OWNED BY tb_team_role_definitions.id;

CREATE TABLE tb_team_role_domain_tombstones (
    team_id bigint NOT NULL,
    source_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE tb_team_role_domain_tombstones IS '삭제된 팀의 사용자 정의 역할 도메인이 command로 재생성되지 않도록 유지하는 영구 fence';

COMMENT ON COLUMN tb_team_role_domain_tombstones.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_role_domain_tombstones.source_occurred_at IS 'cowork-team OWNER 멤버 삭제 상태의 occurredAt';

CREATE TABLE tb_team_role_member_fences (
    team_id bigint NOT NULL,
    account_id bigint NOT NULL,
    state_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE tb_team_role_member_fences IS '멤버 탈퇴 이전 역할 할당이 snapshot generation 교체 후 부활하지 않도록 하는 영구 fence';

COMMENT ON COLUMN tb_team_role_member_fences.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_role_member_fences.account_id IS 'cowork-user의 account id';

CREATE TABLE tb_team_role_tombstones (
    team_id bigint NOT NULL,
    role_id bigint NOT NULL,
    state_occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE tb_team_role_tombstones IS 'preference.team-role.changed full snapshot에서 ROLE_DELETED를 재발행하기 위한 영구 tombstone';

COMMENT ON COLUMN tb_team_role_tombstones.team_id IS 'cowork-team의 tb_teams.id';

COMMENT ON COLUMN tb_team_role_tombstones.role_id IS '삭제된 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_webhook_operations (
    actor_id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_hash character(64) NOT NULL,
    response jsonb NOT NULL,
    secret_ciphertext text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

COMMENT ON COLUMN tb_webhook_operations.actor_id IS 'cowork-user.tb_accounts.id; requesting account ID';

COMMENT ON COLUMN tb_webhook_operations.secret_ciphertext IS 'AES-256-GCM encrypted one-time response; never published to Kafka';

CREATE TABLE tb_webhooks (
    id bigint NOT NULL,
    execution_id uuid DEFAULT gen_random_uuid() NOT NULL,
    channel_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    avatar_url character varying(512),
    is_secure boolean NOT NULL,
    secret_hash character(64),
    is_active boolean DEFAULT true NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    mode_changed_at timestamp with time zone,
    CONSTRAINT ck_tb_webhooks_execution_id_v4 CHECK (((execution_id)::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'::text)),
    CONSTRAINT ck_tb_webhooks_secret CHECK (((deleted AND (secret_hash IS NULL)) OR ((NOT deleted) AND ((is_secure AND (secret_hash IS NOT NULL) AND (secret_hash ~ '^[0-9a-f]{64}$'::text)) OR ((NOT is_secure) AND (secret_hash IS NULL))))))
);

COMMENT ON COLUMN tb_webhooks.execution_id IS 'Random UUIDv4 for the inbound webhook URL; changed only by explicit execution ID rotation';

COMMENT ON COLUMN tb_webhooks.channel_id IS 'cowork-channel.tb_channels.id; Kafka lifecycle projection';

COMMENT ON COLUMN tb_webhooks.created_by IS 'cowork-user.tb_accounts.id; creating account ID';

ALTER TABLE tb_webhooks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME tb_webhooks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY tb_account_role_definitions ALTER COLUMN id SET DEFAULT nextval('preference.tb_account_role_definitions_id_seq'::regclass);

ALTER TABLE ONLY tb_channel_role_policy_command_quarantine ALTER COLUMN id SET DEFAULT nextval('preference.tb_channel_role_policy_command_quarantine_id_seq'::regclass);

ALTER TABLE ONLY tb_github_repo_setting_command_quarantine ALTER COLUMN id SET DEFAULT nextval('preference.tb_github_repo_setting_command_quarantine_id_seq'::regclass);

ALTER TABLE ONLY tb_preference_event_outbox ALTER COLUMN id SET DEFAULT nextval('preference.tb_preference_event_outbox_id_seq'::regclass);

ALTER TABLE ONLY tb_projection_quarantine ALTER COLUMN id SET DEFAULT nextval('preference.tb_projection_quarantine_id_seq'::regclass);

ALTER TABLE ONLY tb_team_role_command_quarantine ALTER COLUMN id SET DEFAULT nextval('preference.tb_team_role_command_quarantine_id_seq'::regclass);

ALTER TABLE ONLY tb_team_role_definitions ALTER COLUMN id SET DEFAULT nextval('preference.tb_team_role_definitions_id_seq'::regclass);

ALTER TABLE ONLY account_channel_notification
    ADD CONSTRAINT account_channel_notification_pkey PRIMARY KEY (account_id, channel_id);

ALTER TABLE ONLY account_project_role
    ADD CONSTRAINT account_project_role_pkey PRIMARY KEY (account_id, project_id, role_name);

ALTER TABLE ONLY tb_channel_lifecycle_projections
    ADD CONSTRAINT pk_tb_channel_lifecycle_projections PRIMARY KEY (channel_id);

ALTER TABLE ONLY tb_channel_role_policies
    ADD CONSTRAINT pk_tb_channel_role_policies PRIMARY KEY (team_id, channel_id, role_id);

ALTER TABLE ONLY tb_channel_role_policy_command_inbox
    ADD CONSTRAINT pk_tb_channel_role_policy_command_inbox PRIMARY KEY (operation_id);

ALTER TABLE ONLY tb_channel_role_policy_command_quarantine
    ADD CONSTRAINT pk_tb_channel_role_policy_command_quarantine PRIMARY KEY (id);

ALTER TABLE ONLY tb_channel_role_policy_tombstones
    ADD CONSTRAINT pk_tb_channel_role_policy_tombstones PRIMARY KEY (team_id, channel_id, role_id);

ALTER TABLE ONLY tb_dm_participant_projections
    ADD CONSTRAINT pk_tb_dm_participant_projections PRIMARY KEY (channel_id, account_id);

ALTER TABLE ONLY tb_github_repo_setting_tombstones
    ADD CONSTRAINT pk_tb_github_repo_setting_tombstones PRIMARY KEY (repo_id);

ALTER TABLE ONLY tb_preference_event_outbox
    ADD CONSTRAINT pk_tb_preference_event_outbox PRIMARY KEY (id);

ALTER TABLE ONLY tb_project_lifecycle_projections
    ADD CONSTRAINT pk_tb_project_lifecycle_projections PRIMARY KEY (project_id);

ALTER TABLE ONLY tb_projection_consumer_barriers
    ADD CONSTRAINT pk_tb_projection_consumer_barriers PRIMARY KEY (consumer_group, topic, partition_id);

ALTER TABLE ONLY tb_projection_consumer_checkpoints
    ADD CONSTRAINT pk_tb_projection_consumer_checkpoints PRIMARY KEY (consumer_group, topic, partition_id);

ALTER TABLE ONLY tb_projection_quarantine
    ADD CONSTRAINT pk_tb_projection_quarantine PRIMARY KEY (id);

ALTER TABLE ONLY tb_team_member_projections
    ADD CONSTRAINT pk_tb_team_member_projections PRIMARY KEY (team_id, account_id);

ALTER TABLE ONLY tb_team_role_assignment_tombstones
    ADD CONSTRAINT pk_tb_team_role_assignment_tombstones PRIMARY KEY (team_id, account_id, role_id);

ALTER TABLE ONLY tb_team_role_member_fences
    ADD CONSTRAINT pk_tb_team_role_member_fences PRIMARY KEY (team_id, account_id);

ALTER TABLE ONLY tb_team_role_tombstones
    ADD CONSTRAINT pk_tb_team_role_tombstones PRIMARY KEY (team_id, role_id);

ALTER TABLE ONLY project_role_definition
    ADD CONSTRAINT project_role_definition_pkey PRIMARY KEY (project_id, role_name);

ALTER TABLE ONLY resource_setting
    ADD CONSTRAINT resource_setting_pkey PRIMARY KEY (resource_id, resource_type);

ALTER TABLE ONLY tb_account_policy_migrations
    ADD CONSTRAINT tb_account_policy_migrations_pkey PRIMARY KEY (account_id);

ALTER TABLE ONLY tb_account_role_assignments
    ADD CONSTRAINT tb_account_role_assignments_pkey PRIMARY KEY (account_id, role_id);

ALTER TABLE ONLY tb_account_role_definitions
    ADD CONSTRAINT tb_account_role_definitions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tb_account_team_roles
    ADD CONSTRAINT tb_account_team_roles_pkey PRIMARY KEY (account_id, team_id, role_id);

ALTER TABLE ONLY tb_github_repo_setting_command_inbox
    ADD CONSTRAINT tb_github_repo_setting_command_inbox_pkey PRIMARY KEY (operation_id);

ALTER TABLE ONLY tb_github_repo_setting_command_quarantine
    ADD CONSTRAINT tb_github_repo_setting_command_quarantine_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tb_initial_resource_access_ledger
    ADD CONSTRAINT tb_initial_resource_access_ledger_pkey PRIMARY KEY (resource_type, resource_id);

ALTER TABLE ONLY tb_project_access_states
    ADD CONSTRAINT tb_project_access_states_pkey PRIMARY KEY (project_id, account_id);

ALTER TABLE ONLY tb_team_policy_migrations
    ADD CONSTRAINT tb_team_policy_migrations_pkey PRIMARY KEY (team_id, account_id);

ALTER TABLE ONLY tb_team_role_command_inbox
    ADD CONSTRAINT tb_team_role_command_inbox_pkey PRIMARY KEY (operation_id);

ALTER TABLE ONLY tb_team_role_command_quarantine
    ADD CONSTRAINT tb_team_role_command_quarantine_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tb_team_role_definitions
    ADD CONSTRAINT tb_team_role_definitions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tb_team_role_domain_tombstones
    ADD CONSTRAINT tb_team_role_domain_tombstones_pkey PRIMARY KEY (team_id);

ALTER TABLE ONLY tb_webhook_operations
    ADD CONSTRAINT tb_webhook_operations_pkey PRIMARY KEY (actor_id, idempotency_key);

ALTER TABLE ONLY tb_webhooks
    ADD CONSTRAINT tb_webhooks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tb_channel_role_policy_command_inbox
    ADD CONSTRAINT uq_tb_channel_role_policy_command_inbox_actor_idempotency UNIQUE (actor_id, idempotency_key);

ALTER TABLE ONLY tb_channel_role_policy_command_quarantine
    ADD CONSTRAINT uq_tb_channel_role_policy_command_quarantine_record UNIQUE (topic, partition_id, record_offset);

ALTER TABLE ONLY tb_github_repo_setting_command_inbox
    ADD CONSTRAINT uq_tb_github_repo_setting_command_inbox_requester_idempotency UNIQUE NULLS NOT DISTINCT (requested_by, idempotency_key);

ALTER TABLE ONLY tb_github_repo_setting_command_quarantine
    ADD CONSTRAINT uq_tb_github_repo_setting_command_quarantine_record UNIQUE (topic, partition_id, record_offset);

ALTER TABLE ONLY tb_projection_quarantine
    ADD CONSTRAINT uq_tb_projection_quarantine_record UNIQUE (consumer_group, topic, partition_id, record_offset);

ALTER TABLE ONLY tb_team_role_command_inbox
    ADD CONSTRAINT uq_tb_team_role_command_inbox_actor_idempotency UNIQUE (actor_id, idempotency_key);

ALTER TABLE ONLY tb_team_role_command_quarantine
    ADD CONSTRAINT uq_tb_team_role_command_quarantine_record UNIQUE (topic, partition_id, record_offset);

ALTER TABLE ONLY tb_team_role_definitions
    ADD CONSTRAINT uq_tb_team_role_definitions_team_id_id UNIQUE (team_id, id);

ALTER TABLE ONLY tb_webhooks
    ADD CONSTRAINT uq_tb_webhooks_execution_id UNIQUE (execution_id);

CREATE INDEX idx_acn_channel_id ON account_channel_notification USING btree (channel_id);

CREATE INDEX idx_apr_project_id ON account_project_role USING btree (project_id);

CREATE INDEX idx_rs_status_expires_at ON resource_setting USING btree (((settings ->> 'status_expires_at'::text))) WHERE ((resource_type = 'ACCOUNT'::resource_type) AND ((settings ->> 'status_expires_at'::text) IS NOT NULL));

CREATE INDEX idx_tb_account_team_roles_role_id ON tb_account_team_roles USING btree (role_id);

CREATE INDEX idx_tb_account_team_roles_team_account_role ON tb_account_team_roles USING btree (team_id, account_id, role_id);

CREATE INDEX idx_tb_channel_role_policies_role ON tb_channel_role_policies USING btree (role_id, team_id, channel_id);

CREATE INDEX idx_tb_channel_role_policy_tombstones_role ON tb_channel_role_policy_tombstones USING btree (role_id, team_id, channel_id);

CREATE INDEX idx_tb_preference_event_outbox_published_at ON tb_preference_event_outbox USING btree (published_at) WHERE (published_at IS NOT NULL);

CREATE INDEX idx_tb_preference_event_outbox_unpublished ON tb_preference_event_outbox USING btree (id) WHERE (published_at IS NULL);

CREATE INDEX idx_tb_team_member_projections_account_active ON tb_team_member_projections USING btree (account_id, deleted);

CREATE INDEX idx_tb_team_role_assignment_tombstones_role ON tb_team_role_assignment_tombstones USING btree (team_id, role_id, account_id);

CREATE INDEX idx_tb_team_role_definitions_team_priority ON tb_team_role_definitions USING btree (team_id, priority DESC, id);

CREATE INDEX idx_tb_webhooks_channel_id ON tb_webhooks USING btree (channel_id);

CREATE UNIQUE INDEX uq_tb_account_role_definitions_bootstrap ON tb_account_role_definitions USING btree (bootstrap_policy) WHERE bootstrap_policy;

CREATE UNIQUE INDEX uq_tb_account_role_definitions_default ON tb_account_role_definitions USING btree (is_default) WHERE is_default;

CREATE UNIQUE INDEX uq_tb_team_role_definitions_managed_policy ON tb_team_role_definitions USING btree (team_id, managed_policy) WHERE (managed_policy IS NOT NULL);

CREATE UNIQUE INDEX uq_tb_team_role_definitions_team_default ON tb_team_role_definitions USING btree (team_id) WHERE is_default;

CREATE UNIQUE INDEX uq_tb_team_role_definitions_team_name_policy ON tb_team_role_definitions USING btree (team_id, name, is_default, COALESCE(managed_policy, ''::character varying));

CREATE TRIGGER trg_acn_state_occurred_at BEFORE INSERT OR UPDATE ON account_channel_notification FOR EACH ROW EXECUTE FUNCTION set_channel_notification_state_occurred_at();

CREATE TRIGGER trg_prd_updated_at BEFORE UPDATE ON project_role_definition FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_rs_state_version BEFORE INSERT OR UPDATE ON resource_setting FOR EACH ROW EXECUTE FUNCTION set_resource_setting_state_version();

CREATE TRIGGER trg_tb_team_role_definitions_updated_at BEFORE UPDATE ON tb_team_role_definitions FOR EACH ROW EXECUTE FUNCTION set_team_role_updated_at_monotonic();

ALTER TABLE ONLY account_project_role
    ADD CONSTRAINT fk_apr_role FOREIGN KEY (project_id, role_name) REFERENCES project_role_definition(project_id, role_name) ON DELETE CASCADE;

ALTER TABLE ONLY tb_account_role_assignments
    ADD CONSTRAINT fk_tb_account_role_assignments_role FOREIGN KEY (role_id) REFERENCES tb_account_role_definitions(id);

ALTER TABLE ONLY tb_account_team_roles
    ADD CONSTRAINT fk_tb_account_team_roles_role FOREIGN KEY (role_id) REFERENCES tb_team_role_definitions(id) ON DELETE CASCADE;

ALTER TABLE ONLY tb_channel_role_policies
    ADD CONSTRAINT fk_tb_channel_role_policies_role FOREIGN KEY (team_id, role_id) REFERENCES tb_team_role_definitions(team_id, id);

INSERT INTO tb_account_role_definitions (name, permissions, is_default)
VALUES ('@everyone', '{"create_team":false}', TRUE);
INSERT INTO tb_account_role_definitions (name, permissions, bootstrap_policy)
VALUES ('Account administrators', '{
    "create_team": true,
    "manage_account_roles": true,
    "manage_global_roadmaps": true,
    "read_all_team_roadmaps": true,
    "create_team_assignments": true,
    "update_team_assignment_status": true,
    "delete_team_assignments": true,
    "manage_chat_search_index": true,
    "manage_chat_message_quarantine": true,
    "view_chat_projections": true,
    "rebuild_chat_projections": true
}', TRUE);

-- 기존 V41은 관리자 역할의 권한 변경을 소비자에게 알리는 이벤트를 남겼습니다.
-- 새 환경도 기존 마이그레이션을 모두 실행한 결과와 같도록 같은 이벤트를 남깁니다.
-- Use the same transaction lock as PreferenceOutboxOrdering before allocating an outbox sequence.
SELECT pg_advisory_xact_lock(0x434F574F524B5052);
INSERT INTO tb_preference_event_outbox (topic, record_key, payload, occurred_at)
SELECT 'preference.account-role.state.v1', 'role:' || id,
       jsonb_build_object('eventType', 'ROLE', 'id', id, 'roleId', id, 'name', name,
                          'isDefault', is_default, 'permissions', permissions,
                          'deleted', deleted, 'occurredAt', state_occurred_at),
       state_occurred_at
FROM tb_account_role_definitions
WHERE bootstrap_policy AND NOT deleted;
