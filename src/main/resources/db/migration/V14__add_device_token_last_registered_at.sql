ALTER TABLE tb_device_tokens
    ADD COLUMN last_registered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER platform,
    ADD INDEX idx_tb_device_tokens_last_registered_at (last_registered_at);
