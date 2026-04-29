DROP TABLE IF EXISTS tb_channel_roles;

ALTER TABLE tb_channels
    MODIFY COLUMN type VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT, VOICE',
    ADD COLUMN view_type VARCHAR(30) NOT NULL DEFAULT 'TEXT'
        COMMENT 'TEXT, VOICE, WEBHOOK, ACCOUNT_SHARE, FILE_SHARE, MEETING_NOTE' AFTER type,
    ADD COLUMN description VARCHAR(500) NULL AFTER view_type,
    ADD COLUMN is_private  TINYINT(1)  NOT NULL DEFAULT 0 AFTER description,
    ADD COLUMN created_by  BIGINT      NOT NULL DEFAULT 0 COMMENT 'cowork-user의 tb_users.id' AFTER is_private,
    ADD INDEX idx_tb_channels_created_by (created_by);

CREATE TABLE tb_channel_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    channel_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL COMMENT 'cowork-user의 tb_users.id',
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_channel_members_channel_id_user_id UNIQUE (channel_id, user_id),
    INDEX idx_tb_channel_members_user_id (user_id),
    CONSTRAINT fk_tb_channel_members_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_webhooks
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    is_secure  TINYINT(1)   NOT NULL DEFAULT 0,
    token      VARCHAR(255) NULL,
    avatar_url VARCHAR(512) NULL,
    created_by BIGINT       NOT NULL COMMENT 'cowork-user의 tb_users.id',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_webhooks_token UNIQUE (token),
    INDEX idx_tb_webhooks_channel_id (channel_id),
    CONSTRAINT fk_tb_webhooks_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_threads
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id        BIGINT       NOT NULL,
    name              VARCHAR(100) NOT NULL,
    parent_message_id VARCHAR(24)  NOT NULL COMMENT 'cowork-chat.messages의 MongoDB ObjectId',
    is_archived       TINYINT(1)   NOT NULL DEFAULT 0,
    created_by        BIGINT       NOT NULL COMMENT 'cowork-user의 tb_users.id',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_threads_parent_message_id UNIQUE (parent_message_id),
    INDEX idx_tb_threads_channel_id (channel_id),
    CONSTRAINT fk_tb_threads_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
