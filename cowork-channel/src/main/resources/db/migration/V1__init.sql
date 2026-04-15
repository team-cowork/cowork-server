CREATE TABLE tb_channels
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    team_id    BIGINT       NOT NULL COMMENT '채널 소속 팀 ID',
    type       VARCHAR(20)  NOT NULL DEFAULT 'text' COMMENT 'text, voice',
    name       VARCHAR(100) NOT NULL,
    notice     VARCHAR(500),
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_channels_team_id_type_id (team_id, type, id),
    INDEX idx_tb_channels_team_id_name (team_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_roles
(
    channel_id BIGINT      NOT NULL,
    role       VARCHAR(50) NOT NULL,
    PRIMARY KEY (channel_id, role),
    INDEX idx_tb_channel_roles_role_channel_id (role, channel_id),
    CONSTRAINT fk_tb_channel_roles_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
