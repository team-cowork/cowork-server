-- TODO(2026-11-18T00:00:00Z): Remove this one-time policy backfill when migration history is consolidated.
-- Preserve applied Flyway checksums until that consolidation.
-- 음성 채널 로그 읽기 권한은 기본으로 거부한다. 저장된 역할 권한을 전체 상태로 맞추고 시작 snapshot이 다시 발행하게 한다.
UPDATE tb_team_role_definitions
SET permissions = permissions || '{"voice_log_read":false}'::jsonb,
    updated_at = GREATEST(clock_timestamp(), updated_at + INTERVAL '1 microsecond')
WHERE jsonb_typeof(permissions) = 'object'
  AND NOT permissions ? 'voice_log_read';
