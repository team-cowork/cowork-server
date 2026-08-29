ALTER TABLE tb_team_role_definitions
    ADD CONSTRAINT uq_tb_team_role_definitions_team_id_id UNIQUE (team_id, id);

CREATE TABLE tb_channel_role_policies
(
    team_id           BIGINT      NOT NULL,
    channel_id        BIGINT      NOT NULL,
    role_id           BIGINT      NOT NULL,
    permissions       JSONB       NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_channel_role_policies PRIMARY KEY (team_id, channel_id, role_id),
    CONSTRAINT fk_tb_channel_role_policies_role
        FOREIGN KEY (team_id, role_id) REFERENCES tb_team_role_definitions (team_id, id),
    CONSTRAINT ck_tb_channel_role_policies_permissions CHECK (
        jsonb_typeof(permissions) = 'object'
        AND permissions ? 'message_read'
        AND permissions - 'message_read' = '{}'::jsonb
        AND jsonb_typeof(permissions -> 'message_read') = 'boolean'
    )
);

COMMENT ON TABLE tb_channel_role_policies IS
    '채널별 사용자 정의 팀 역할 권한의 authoritative full state';
COMMENT ON COLUMN tb_channel_role_policies.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_channel_role_policies.channel_id IS 'cowork-channel의 tb_channels.id';
COMMENT ON COLUMN tb_channel_role_policies.role_id IS 'cowork-preference의 tb_team_role_definitions.id';

CREATE INDEX idx_tb_channel_role_policies_role
    ON tb_channel_role_policies (role_id, team_id, channel_id);

CREATE TABLE tb_channel_role_policy_tombstones
(
    team_id           BIGINT      NOT NULL,
    channel_id        BIGINT      NOT NULL,
    role_id           BIGINT      NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_channel_role_policy_tombstones PRIMARY KEY (team_id, channel_id, role_id)
);

COMMENT ON TABLE tb_channel_role_policy_tombstones IS
    'compacted state topic에서 삭제 상태를 full snapshot으로 재발행하기 위한 영구 tombstone';
COMMENT ON COLUMN tb_channel_role_policy_tombstones.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_channel_role_policy_tombstones.channel_id IS 'cowork-channel의 tb_channels.id';
COMMENT ON COLUMN tb_channel_role_policy_tombstones.role_id IS
    '삭제될 수 있는 cowork-preference의 tb_team_role_definitions.id';

CREATE INDEX idx_tb_channel_role_policy_tombstones_role
    ON tb_channel_role_policy_tombstones (role_id, team_id, channel_id);

CREATE TABLE tb_channel_role_policy_command_inbox
(
    operation_id             CHAR(36)     NOT NULL,
    actor_id                 BIGINT       NOT NULL,
    idempotency_key          VARCHAR(128) NOT NULL,
    request_hash             CHAR(64)     NOT NULL,
    command_type             VARCHAR(10)  NOT NULL,
    team_id                  BIGINT       NOT NULL,
    channel_id               BIGINT       NOT NULL,
    role_id                  BIGINT       NOT NULL,
    actor_membership_version TEXT         NOT NULL,
    permissions              JSONB,
    result                   JSONB        NOT NULL,
    processed_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_channel_role_policy_command_inbox PRIMARY KEY (operation_id),
    CONSTRAINT uq_tb_channel_role_policy_command_inbox_actor_idempotency
        UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_tb_channel_role_policy_command_inbox_type
        CHECK (command_type IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_tb_channel_role_policy_command_inbox_shape CHECK (
        (
            command_type = 'UPSERT'
            AND permissions IS NOT NULL
            AND jsonb_typeof(permissions) = 'object'
            AND permissions ? 'message_read'
            AND permissions - 'message_read' = '{}'::jsonb
            AND jsonb_typeof(permissions -> 'message_read') = 'boolean'
        )
        OR (command_type = 'DELETE' AND permissions IS NULL)
    )
);

COMMENT ON COLUMN tb_channel_role_policy_command_inbox.actor_id IS 'cowork-user의 account id';
COMMENT ON COLUMN tb_channel_role_policy_command_inbox.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_channel_role_policy_command_inbox.channel_id IS 'cowork-channel의 tb_channels.id';
COMMENT ON COLUMN tb_channel_role_policy_command_inbox.role_id IS
    'command 처리 시 검증한 cowork-preference의 tb_team_role_definitions.id';

CREATE TABLE tb_channel_role_policy_command_quarantine
(
    id            BIGSERIAL    NOT NULL,
    topic         VARCHAR(255) NOT NULL,
    partition_id  INTEGER      NOT NULL,
    record_offset BIGINT       NOT NULL,
    record_key    TEXT,
    payload       TEXT,
    reason        TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tb_channel_role_policy_command_quarantine PRIMARY KEY (id),
    CONSTRAINT uq_tb_channel_role_policy_command_quarantine_record
        UNIQUE (topic, partition_id, record_offset),
    CONSTRAINT ck_tb_channel_role_policy_command_quarantine_partition CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_channel_role_policy_command_quarantine_offset CHECK (record_offset >= 0)
);

ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic;

ALTER TABLE tb_preference_event_outbox
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
        'preference.channel-notification.changed',
        'preference.team-role.changed',
        'preference.team-role.command-result',
        'preference.github-repo.setting.state',
        'preference.github-repo.setting.result',
        'preference.channel-role-policy.changed',
        'preference.channel-role-policy.command-result',
        'preference.status.changed',
        'preference.team.setting.changed'
    ));
