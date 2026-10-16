CREATE FUNCTION legacy_team_role_permissions(legacy JSONB)
    RETURNS JSONB AS
$$
SELECT (
    SELECT jsonb_object_agg(key, FALSE)
    FROM unnest(ARRAY[
        'manage_team', 'manage_roles', 'manage_invites', 'kick_members', 'manage_channels', 'manage_projects',
        'manage_github', 'message_read', 'message_send', 'thread_reply', 'file_attach', 'reaction_add',
        'message_pin', 'message_manage', 'slash_command_use', 'github_issue_create', 'channel_manage',
        'channel_member_manage', 'voice_connect', 'voice_speak', 'screen_share', 'live_broadcast'
    ]) AS key
) || COALESCE((
    SELECT jsonb_object_agg(lower(granted.value), TRUE)
    FROM jsonb_array_elements_text(legacy) AS granted(value)
    WHERE lower(granted.value) IN (
        'manage_team', 'manage_roles', 'manage_invites', 'kick_members', 'manage_channels', 'manage_projects',
        'manage_github', 'message_read', 'message_send', 'thread_reply', 'file_attach', 'reaction_add',
        'message_pin', 'message_manage', 'slash_command_use', 'github_issue_create', 'channel_manage',
        'channel_member_manage', 'voice_connect', 'voice_speak', 'screen_share', 'live_broadcast'
    )
), '{}'::JSONB)
$$ LANGUAGE sql IMMUTABLE;

UPDATE tb_team_role_definitions
SET permissions = legacy_team_role_permissions(permissions)
WHERE jsonb_typeof(permissions) = 'array';

UPDATE tb_team_role_command_inbox
SET result = jsonb_set(
    result,
    '{role,permissions}',
    legacy_team_role_permissions(result -> 'role' -> 'permissions')
)
WHERE jsonb_typeof(result -> 'role' -> 'permissions') = 'array';

DROP FUNCTION legacy_team_role_permissions(JSONB);

ALTER TABLE tb_team_role_definitions
    ALTER COLUMN permissions SET DEFAULT '{}'::JSONB,
    ADD CONSTRAINT ck_tb_team_role_definitions_permissions CHECK (
        jsonb_typeof(permissions) = 'object'
        AND NOT jsonb_path_exists(permissions, '$.* ? (@.type() != "boolean")')
    );

COMMENT ON COLUMN tb_team_role_definitions.permissions IS
    '역할 기본 권한. 권한 키별 boolean 전체 상태이며 누락된 키는 false';

ALTER TABLE tb_channel_role_policies
    DROP CONSTRAINT ck_tb_channel_role_policies_permissions,
    ADD CONSTRAINT ck_tb_channel_role_policies_permissions CHECK (
        jsonb_typeof(permissions) = 'object'
        AND permissions <> '{}'::JSONB
        AND NOT jsonb_path_exists(permissions, '$.* ? (@.type() != "boolean")')
    );

COMMENT ON COLUMN tb_channel_role_policies.permissions IS
    '채널별 역할 권한 덮어쓰기. true는 허용, false는 거부이며 누락된 키는 역할 기본 권한을 따른다';

ALTER TABLE tb_channel_role_policy_command_inbox
    DROP CONSTRAINT ck_tb_channel_role_policy_command_inbox_shape,
    ADD CONSTRAINT ck_tb_channel_role_policy_command_inbox_shape CHECK (
        (
            command_type = 'UPSERT'
            AND permissions IS NOT NULL
            AND jsonb_typeof(permissions) = 'object'
            AND permissions <> '{}'::JSONB
            AND NOT jsonb_path_exists(permissions, '$.* ? (@.type() != "boolean")')
        )
        OR (command_type = 'DELETE' AND permissions IS NULL)
    );

ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic;

ALTER TABLE tb_preference_event_outbox
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic CHECK (topic IN (
        'preference.channel-notification.changed',
        'preference.team-role.changed',
        'preference.team-role.command-result',
        'preference.team-role.state.v2',
        'preference.team-role.result.v2',
        'preference.github-repo.setting.state',
        'preference.github-repo.setting.result',
        'preference.channel-role-policy.changed',
        'preference.channel-role-policy.command-result',
        'preference.channel-role-policy.state.v2',
        'preference.channel-role-policy.result.v2',
        'preference.status.changed',
        'preference.team.setting.changed'
    ));
