-- 삭제된 채널은 접근 판정에 쓰이지 않으므로 채우는 값은 판정에 영향을 주지 않는다.
-- 삭제되지 않은 NULL 행은 채울 값을 알 수 없으므로 아래 NOT NULL 제약에서 마이그레이션을 실패시킨다.
UPDATE tb_channel_lifecycle_projections
SET is_private   = COALESCE(is_private, true),
    channel_type = COALESCE(channel_type, 'TEXT')
WHERE deleted
  AND (is_private IS NULL OR channel_type IS NULL);

ALTER TABLE tb_channel_lifecycle_projections
    ALTER COLUMN is_private SET NOT NULL,
    ALTER COLUMN channel_type SET NOT NULL;

COMMENT ON COLUMN tb_channel_lifecycle_projections.is_private IS 'cowork-channel의 tb_channels.is_private';

COMMENT ON COLUMN tb_channel_lifecycle_projections.channel_type IS 'cowork-channel의 tb_channels.type';
