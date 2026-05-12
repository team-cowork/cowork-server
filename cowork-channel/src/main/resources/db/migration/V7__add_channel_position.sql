ALTER TABLE tb_channels
    ADD COLUMN position INT NOT NULL DEFAULT 0 COMMENT '팀 내 채널 정렬 순서' AFTER is_private,
    ADD INDEX idx_tb_channels_position (position);
