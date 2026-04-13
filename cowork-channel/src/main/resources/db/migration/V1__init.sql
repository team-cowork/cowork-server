CREATE TABLE tb_channels
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    team_id     BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL DEFAULT 'TEXT'
                             COMMENT 'TEXT, VOICE, WEBHOOK, ACCOUNT_SHARE, FILE_SHARE, MEETING_NOTE',
    description VARCHAR(500),
    is_private  TINYINT(1)   NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_channels_team_id (team_id),
    INDEX idx_tb_channels_type (type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    channel_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_channel_members (channel_id, user_id),
    INDEX idx_tb_channel_members_user_id (user_id),
    CONSTRAINT fk_tb_channel_members_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- WEBHOOK 타입 채널의 토큰/설정 저장
-- is_secure=0: URL만 맞으면 누구나 메시지 전송 가능
-- is_secure=1: 요청 헤더에 token도 함께 포함해야 수신 처리
CREATE TABLE tb_webhooks
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    is_secure  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '보안 모드 활성화 여부',
    token      VARCHAR(255)          DEFAULT NULL COMMENT '보안 모드 ON일 때만 사용. 헤더에 포함 필요',
    avatar_url VARCHAR(512),
    created_by BIGINT       NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_webhooks_token (token),
    INDEX idx_tb_webhooks_channel_id (channel_id),
    CONSTRAINT fk_tb_webhooks_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 스레드 메타데이터 (실제 메시지는 MongoDB에 저장)
-- parent_message_id: 스레드가 시작된 MongoDB 메시지의 ObjectId
CREATE TABLE tb_threads
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id        BIGINT       NOT NULL,
    name              VARCHAR(100) NOT NULL,
    parent_message_id VARCHAR(24)  NOT NULL COMMENT 'MongoDB ObjectId (cowork-chat의 messages 컬렉션)',
    created_by        BIGINT       NOT NULL COMMENT 'cowork-user의 tb_user_profiles.id',
    is_archived       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_threads_parent_message (parent_message_id),
    INDEX idx_tb_threads_channel_id (channel_id),
    CONSTRAINT fk_tb_threads_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
