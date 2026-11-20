-- Use the same transaction lock as PreferenceOutboxOrdering before allocating an outbox sequence.
SELECT pg_advisory_xact_lock(0x434F574F524B5052);

WITH changed AS (
    UPDATE tb_account_role_definitions
    SET permissions = permissions || '{
            "manage_global_roadmaps": true,
            "read_all_team_roadmaps": true,
            "create_team_assignments": true,
            "update_team_assignment_status": true,
            "delete_team_assignments": true,
            "manage_chat_search_index": true,
            "manage_chat_message_quarantine": true,
            "view_chat_projections": true,
            "rebuild_chat_projections": true
        }'::jsonb,
        state_occurred_at = GREATEST(clock_timestamp(), state_occurred_at + INTERVAL '1 microsecond')
    WHERE bootstrap_policy AND NOT deleted AND permissions @> '{"manage_account_roles":true}'::jsonb
    RETURNING id, name, permissions, is_default, deleted, state_occurred_at
)
INSERT INTO tb_preference_event_outbox (topic, record_key, payload, occurred_at)
SELECT 'preference.account-role.state.v1', 'role:' || id,
       jsonb_build_object('eventType', 'ROLE', 'id', id, 'roleId', id, 'name', name,
                          'isDefault', is_default, 'permissions', permissions,
                          'deleted', deleted, 'occurredAt', state_occurred_at),
       state_occurred_at
FROM changed;
