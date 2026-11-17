-- TODO(2026-11-17T11:46:21Z): Remove this one-time policy backfill when migration history is consolidated.
-- Preserve applied Flyway checksums until that consolidation.
UPDATE tb_account_role_definitions
SET permissions = permissions || '{"create_team":false}'::jsonb,
    state_occurred_at = GREATEST(clock_timestamp(), state_occurred_at + INTERVAL '1 microsecond')
WHERE is_default AND NOT deleted;

UPDATE tb_team_role_definitions
SET permissions = permissions - 'create_team',
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE permissions ? 'create_team';
