CREATE TABLE tb_resource_settings
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(30) NOT NULL COMMENT 'ACCOUNT, TEAM, PROJECT, TEXT_CHANNEL, VOICE_CHANNEL',
    resource_id   BIGINT      NOT NULL,
    settings      JSON        NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_resource_settings_type_id (resource_type, resource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_notification_settings
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    channel_id BIGINT      NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_channel_notification_settings_user_channel (user_id, channel_id),
    INDEX idx_tb_channel_notification_settings_channel_enabled (channel_id, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
