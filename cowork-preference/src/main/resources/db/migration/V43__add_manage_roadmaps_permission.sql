-- TODO(2026-11-24T00:00:00Z): Remove this one-time backfill when migration history is consolidated.
-- Roadmap management moves from `manage_team` to `manage_roadmaps`, so existing team roles keep their current access.
-- Managed owner and admin policies keep only `administrator`, which already allows every roadmap action.
-- The startup snapshot republishes every team role, and the newer updated_at makes consumers apply it.
-- Preserve applied Flyway checksums until that consolidation.
UPDATE tb_team_role_definitions
SET permissions = permissions || jsonb_build_object(
        'manage_roadmaps', COALESCE((permissions ->> 'manage_team')::boolean, false)
    ),
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE managed_policy IS NULL
  AND NOT permissions ? 'manage_roadmaps';
