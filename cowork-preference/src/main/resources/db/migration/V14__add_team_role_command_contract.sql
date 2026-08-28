CREATE TABLE tb_team_member_projections
(
    team_id            BIGINT      NOT NULL,
    account_id         BIGINT      NOT NULL,
    built_in_role      VARCHAR(20) NOT NULL,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tb_team_member_projections PRIMARY KEY (team_id, account_id),
    CONSTRAINT ck_tb_team_member_projections_role CHECK (built_in_role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

COMMENT ON COLUMN tb_team_member_projections.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_member_projections.account_id IS 'cowork-user의 account id';

CREATE INDEX idx_tb_team_member_projections_account_active
    ON tb_team_member_projections (account_id, deleted);

CREATE TABLE tb_team_role_command_inbox
(
    operation_id CHAR(36) PRIMARY KEY,
    actor_id     BIGINT       NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    command_type VARCHAR(20)  NOT NULL,
    team_id      BIGINT       NOT NULL,
    result       JSONB        NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tb_team_role_command_inbox_type CHECK (
        command_type IN ('CREATE', 'UPDATE', 'DELETE', 'ASSIGN', 'REVOKE')
    ),
    CONSTRAINT uq_tb_team_role_command_inbox_actor_idempotency UNIQUE (actor_id, idempotency_key)
);

COMMENT ON COLUMN tb_team_role_command_inbox.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_role_command_inbox.actor_id IS 'cowork-user의 account id';

CREATE TABLE tb_team_role_command_quarantine
(
    id             BIGSERIAL    PRIMARY KEY,
    topic          VARCHAR(255) NOT NULL,
    partition_id   INTEGER      NOT NULL,
    record_offset  BIGINT       NOT NULL,
    record_key     TEXT,
    payload        TEXT,
    reason         TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tb_team_role_command_quarantine_record UNIQUE (topic, partition_id, record_offset),
    CONSTRAINT ck_tb_team_role_command_quarantine_partition CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_team_role_command_quarantine_offset CHECK (record_offset >= 0)
);

ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic;

ALTER TABLE tb_preference_event_outbox
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
        'preference.channel-notification.changed',
        'preference.team-role.changed',
        'preference.team-role.command-result',
        'preference.status.changed',
        'preference.team.setting.changed'
    ));
