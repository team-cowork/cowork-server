-- TODO(2026-11-26T00:00:00Z): Remove this one-time backfill when migration history is consolidated.
-- Installed applets need `use_applets`, so every member keeps running them through the default @everyone role.
-- Other unmanaged roles get the key as false to keep the full permission state.
-- Managed owner and admin policies keep only `administrator`, which already allows applet use.
-- The startup snapshot republishes every team role, and the newer updated_at makes consumers apply it.
-- Preserve applied Flyway checksums until that consolidation.
UPDATE tb_team_role_definitions
SET permissions = permissions || jsonb_build_object('use_applets', is_default),
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE managed_policy IS NULL
  AND NOT permissions ? 'use_applets';
