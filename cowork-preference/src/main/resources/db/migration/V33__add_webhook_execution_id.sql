-- PostgreSQL provides random UUIDv4 generation without an extension.
ALTER TABLE tb_webhooks ADD COLUMN execution_id UUID;
UPDATE tb_webhooks SET execution_id = gen_random_uuid() WHERE execution_id IS NULL;
ALTER TABLE tb_webhooks ALTER COLUMN execution_id SET DEFAULT gen_random_uuid();
ALTER TABLE tb_webhooks ALTER COLUMN execution_id SET NOT NULL;
ALTER TABLE tb_webhooks ADD CONSTRAINT uq_tb_webhooks_execution_id UNIQUE (execution_id);
ALTER TABLE tb_webhooks ADD CONSTRAINT ck_tb_webhooks_execution_id_v4 CHECK (
    execution_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
);
COMMENT ON COLUMN tb_webhooks.execution_id IS 'Random UUIDv4 for the inbound webhook URL; immutable after assignment';

-- Preserve queued v1 records; all new owner events and full snapshots use v2.
ALTER TABLE tb_preference_event_outbox DROP CONSTRAINT ck_tb_preference_event_outbox_topic;
ALTER TABLE tb_preference_event_outbox ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
    'preference.channel-notification.changed', 'preference.team-role.changed', 'preference.team-role.command-result',
    'preference.team-role.state.v2', 'preference.team-role.result.v2', 'preference.github-repo.setting.state',
    'preference.github-repo.setting.result', 'preference.channel-role-policy.changed',
    'preference.channel-role-policy.command-result', 'preference.channel-role-policy.state.v2',
    'preference.channel-role-policy.result.v2', 'preference.status.changed', 'preference.team.setting.changed',
    'preference.project-access.state.v1', 'preference.account-setting.state.v1',
    'preference.team-setting.state.v1', 'preference.text-channel-setting.state.v1',
    'preference.webhook.state.v1', 'preference.webhook.state.v2'
));
