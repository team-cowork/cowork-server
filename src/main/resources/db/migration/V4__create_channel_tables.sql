CREATE TABLE tb_channels
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    team_id     BIGINT       NOT NULL,
    project_id  BIGINT                DEFAULT NULL,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'TEXT, VOICE',
    view_type   VARCHAR(30)  NOT NULL COMMENT 'TEXT, VOICE, WEBHOOK, ACCOUNT_SHARE, FILE_SHARE, MEETING_NOTE',
    description VARCHAR(500)          DEFAULT NULL,
    is_private  BOOLEAN      NOT NULL DEFAULT FALSE,
    position    INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_channels_team_position (team_id, position),
    INDEX idx_tb_channels_project_id (project_id),
    CONSTRAINT fk_tb_channels_team FOREIGN KEY (team_id) REFERENCES tb_teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tb_channels_project FOREIGN KEY (project_id) REFERENCES tb_projects (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    channel_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_channel_members_channel_user (channel_id, user_id),
    INDEX idx_tb_channel_members_user_id (user_id),
    CONSTRAINT fk_tb_channel_members_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_tb_channel_members_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
