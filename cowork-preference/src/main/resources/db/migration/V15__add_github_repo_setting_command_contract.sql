ALTER TABLE resource_setting
    ADD COLUMN state_occurred_at TIMESTAMPTZ;

UPDATE resource_setting
SET state_occurred_at = updated_at
WHERE state_occurred_at IS NULL;

ALTER TABLE resource_setting
    ALTER COLUMN state_occurred_at SET NOT NULL,
    ALTER COLUMN state_occurred_at SET DEFAULT clock_timestamp();

CREATE OR REPLACE FUNCTION set_resource_setting_state_version()
    RETURNS TRIGGER AS
$$
DECLARE
    next_version TIMESTAMPTZ;
BEGIN
    next_version = clock_timestamp();
    IF TG_OP = 'UPDATE' THEN
        next_version = GREATEST(next_version, OLD.state_occurred_at + INTERVAL '1 microsecond');
    END IF;
    NEW.updated_at = next_version;
    NEW.state_occurred_at = next_version;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_rs_updated_at ON resource_setting;

CREATE TRIGGER trg_rs_state_version
    BEFORE INSERT OR UPDATE ON resource_setting
    FOR EACH ROW EXECUTE FUNCTION set_resource_setting_state_version();

CREATE TABLE tb_github_repo_setting_command_inbox
(
    operation_id      CHAR(36)     PRIMARY KEY,
    idempotency_key   VARCHAR(128) NOT NULL,
    command_type      VARCHAR(10)  NOT NULL,
    repo_id           BIGINT       NOT NULL,
    requested_by      BIGINT,
    label_auto_apply  BOOLEAN,
    command_occurred_at TEXT         NOT NULL,
    result            JSONB,
    processed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tb_github_repo_setting_command_inbox_type CHECK (command_type IN ('UPDATE', 'DELETE')),
    CONSTRAINT ck_tb_github_repo_setting_command_inbox_shape CHECK (
        (command_type = 'UPDATE' AND requested_by IS NOT NULL AND label_auto_apply IS NOT NULL AND result IS NOT NULL)
        OR
        (command_type = 'DELETE' AND label_auto_apply IS NULL AND result IS NULL)
    ),
    CONSTRAINT uq_tb_github_repo_setting_command_inbox_requester_idempotency
        UNIQUE NULLS NOT DISTINCT (requested_by, idempotency_key)
);

COMMENT ON COLUMN tb_github_repo_setting_command_inbox.repo_id IS 'cowork-project의 GitHub repository id';
COMMENT ON COLUMN tb_github_repo_setting_command_inbox.requested_by IS 'cowork-user의 account id';

CREATE TABLE tb_github_repo_setting_command_quarantine
(
    id             BIGSERIAL    PRIMARY KEY,
    topic          VARCHAR(255) NOT NULL,
    partition_id   INTEGER      NOT NULL,
    record_offset  BIGINT       NOT NULL,
    record_key     TEXT,
    payload        TEXT,
    reason         TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tb_github_repo_setting_command_quarantine_record
        UNIQUE (topic, partition_id, record_offset),
    CONSTRAINT ck_tb_github_repo_setting_command_quarantine_partition CHECK (partition_id >= 0),
    CONSTRAINT ck_tb_github_repo_setting_command_quarantine_offset CHECK (record_offset >= 0)
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
        'preference.status.changed',
        'preference.team.setting.changed'
    ));
