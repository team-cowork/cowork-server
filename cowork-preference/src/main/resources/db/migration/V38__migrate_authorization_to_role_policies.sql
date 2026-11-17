ALTER TABLE tb_team_role_definitions ADD COLUMN managed_policy VARCHAR(16);
ALTER TABLE tb_team_role_definitions ADD CONSTRAINT ck_tb_team_role_definitions_managed_policy
    CHECK (managed_policy IN ('owner', 'admin'));
CREATE UNIQUE INDEX uq_tb_team_role_definitions_managed_policy
    ON tb_team_role_definitions (team_id, managed_policy) WHERE managed_policy IS NOT NULL;
ALTER TABLE tb_team_role_definitions DROP CONSTRAINT uq_tb_team_role_definitions_team_name_default;
CREATE UNIQUE INDEX uq_tb_team_role_definitions_team_name_policy
    ON tb_team_role_definitions (team_id, name, is_default, COALESCE(managed_policy, ''));

-- TODO(2026-11-17T06:59:44Z): Remove this priority backfill after every environment has applied V38.
-- Run Flyway repair when removing statements from an applied migration.
UPDATE tb_team_role_definitions SET priority = 2147483646, updated_at = clock_timestamp() WHERE priority = 2147483647;

-- TODO(2026-11-17T06:59:44Z): Drop this ledger in a new migration after legacy team ADMIN migration is retired.
-- This ledger survives projection rebuilds, so an old ADMIN snapshot cannot restore a revoked policy.
CREATE TABLE tb_team_policy_migrations (
    team_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, account_id)
);
COMMENT ON COLUMN tb_team_policy_migrations.team_id IS 'cowork-team의 tb_teams.id';
COMMENT ON COLUMN tb_team_policy_migrations.account_id IS 'cowork-user의 account id';

-- TODO(2026-11-17T06:59:44Z): Remove the following team role and assignment backfills after every environment has applied V38.
-- Keep runtime owner-policy binding; run Flyway repair when removing applied SQL.
INSERT INTO tb_team_role_definitions
    (team_id, name, color_hex, priority, mentionable, permissions, managed_policy)
SELECT DISTINCT m.team_id, p.name, '#99AAB5', p.priority, FALSE, p.permissions::jsonb, p.policy
FROM tb_team_member_projections m
CROSS JOIN (VALUES
    ('owner', 'Owner', 2147483647, '{"administrator":true,"transfer_team_ownership":true}'),
    ('admin', 'Admin', 2147483646, '{"administrator":true}')
) AS p(policy, name, priority, permissions)
WHERE NOT m.deleted AND NOT EXISTS (
    SELECT 1 FROM tb_team_role_domain_tombstones t WHERE t.team_id = m.team_id
);

INSERT INTO tb_account_team_roles (account_id, team_id, role_id, source_membership_version, updated_at)
SELECT m.account_id, m.team_id, r.id, m.source_occurred_at,
       GREATEST(clock_timestamp(), COALESCE(f.state_occurred_at + INTERVAL '1 microsecond', '-infinity'::timestamptz))
FROM tb_team_member_projections m
JOIN tb_team_role_definitions r ON r.team_id = m.team_id AND r.managed_policy = lower(m.built_in_role)
LEFT JOIN tb_team_role_member_fences f ON f.team_id = m.team_id AND f.account_id = m.account_id
WHERE NOT m.deleted;

INSERT INTO tb_team_policy_migrations SELECT team_id, account_id FROM tb_team_member_projections;
UPDATE tb_team_role_definitions SET permissions = permissions || '{"create_team":true}'::jsonb WHERE is_default;

-- TODO(2026-11-17T06:59:44Z): Drop this ledger and bootstrap_policy in a new migration after all legacy platform administrators have policy assignments.
CREATE TABLE tb_account_policy_migrations (account_id BIGINT PRIMARY KEY);
COMMENT ON COLUMN tb_account_policy_migrations.account_id IS 'cowork-user의 account id; 기존 플랫폼 역할의 최초 정책 이관 기록';

CREATE TABLE tb_account_role_definitions (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    permissions JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    bootstrap_policy BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    state_occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_tb_account_role_definitions_permissions CHECK (
        jsonb_typeof(permissions) = 'object' AND NOT jsonb_path_exists(permissions, '$.* ? (@.type() != "boolean")')
    )
);
CREATE UNIQUE INDEX uq_tb_account_role_definitions_default ON tb_account_role_definitions (is_default) WHERE is_default;
CREATE UNIQUE INDEX uq_tb_account_role_definitions_bootstrap ON tb_account_role_definitions (bootstrap_policy) WHERE bootstrap_policy;
CREATE TABLE tb_account_role_assignments (
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    state_occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_id, role_id),
    CONSTRAINT fk_tb_account_role_assignments_role FOREIGN KEY (role_id) REFERENCES tb_account_role_definitions(id)
);
COMMENT ON COLUMN tb_account_role_assignments.account_id IS 'cowork-user의 account id';
INSERT INTO tb_account_role_definitions (name, permissions, is_default)
VALUES ('@everyone', '{"create_team":true}', TRUE);
INSERT INTO tb_account_role_definitions (name, permissions, bootstrap_policy)
VALUES ('Account administrators', '{"create_team":true,"manage_account_roles":true}', TRUE);

ALTER TABLE tb_preference_event_outbox DROP CONSTRAINT ck_tb_preference_event_outbox_topic;
ALTER TABLE tb_preference_event_outbox ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
    'preference.channel-notification.changed', 'preference.team-role.changed', 'preference.team-role.command-result',
    'preference.team-role.state.v2', 'preference.team-role.result.v2',
    'preference.github-repo.setting.state', 'preference.github-repo.setting.result',
    'preference.channel-role-policy.changed', 'preference.channel-role-policy.command-result',
    'preference.channel-role-policy.state.v2', 'preference.channel-role-policy.result.v2',
    'preference.status.changed', 'preference.team.setting.changed', 'preference.project-access.state.v1',
    'preference.account-setting.state.v1', 'preference.team-setting.state.v1', 'preference.text-channel-setting.state.v1',
    'preference.webhook.state.v2', 'preference.voice-channel-setting.state.v1', 'preference.account-role.state.v1'
));
