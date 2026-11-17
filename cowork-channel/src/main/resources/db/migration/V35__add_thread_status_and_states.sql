-- 이 컬럼이 생기기 전에 만든 스레드는 시작 메시지를 검증하지 않았지만, 지금까지 쓰던 스레드이므로 확정 상태로 둡니다.
ALTER TABLE tb_threads
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING: 시작 메시지 검증 대기, ACTIVE: 확정' AFTER is_archived;

CREATE TABLE tb_thread_states
(
    thread_id         BIGINT       NOT NULL PRIMARY KEY COMMENT '스레드 삭제 후에도 발행 상태를 보존합니다.',
    channel_id        BIGINT       NOT NULL,
    name              VARCHAR(100) NOT NULL,
    parent_message_id VARCHAR(24)  NOT NULL COMMENT 'cowork-chat.messages의 MongoDB ObjectId',
    created_by        BIGINT       NOT NULL COMMENT 'cowork-user의 tb_users.id',
    status            VARCHAR(20)  NOT NULL,
    archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    state_occurred_at DATETIME(6)  NOT NULL,
    INDEX idx_tb_thread_states_channel_id (channel_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_thread_states (thread_id, channel_id, name, parent_message_id, created_by, status, archived, deleted, state_occurred_at)
SELECT id, channel_id, name, parent_message_id, created_by, status, is_archived, FALSE, UTC_TIMESTAMP(6)
FROM tb_threads;
