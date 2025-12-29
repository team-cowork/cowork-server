CREATE TABLE tb_voice_sessions
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL,
    room_name  VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL COMMENT 'ACTIVE, ENDED',
    started_at DATETIME(6)  NOT NULL,
    ended_at   DATETIME(6)           DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_voice_sessions_room_name (room_name),
    INDEX idx_tb_voice_sessions_channel_status (channel_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_voice_participants
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    joined_at  DATETIME(6) NOT NULL,
    left_at    DATETIME(6)          DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_tb_voice_participants_session_left (session_id, left_at),
    CONSTRAINT fk_tb_voice_participants_session FOREIGN KEY (session_id) REFERENCES tb_voice_sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
