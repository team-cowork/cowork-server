-- TODO(2026-11-22T00:00:00Z): Remove this one-time backfill when migration history is consolidated.
-- Existing team roles may create calendar events but may not change other people's events by default.
-- Managed owner and admin policies keep only `administrator`, which already allows every calendar action.
-- The startup snapshot republishes every team role, and the newer updated_at makes consumers apply it.
-- Preserve applied Flyway checksums until that consolidation.
UPDATE tb_team_role_definitions
SET permissions = permissions || '{
        "calendar_event_create": true,
        "calendar_event_update": false,
        "calendar_event_delete": false
    }'::jsonb,
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE managed_policy IS NULL
  AND NOT permissions ? 'calendar_event_create';
