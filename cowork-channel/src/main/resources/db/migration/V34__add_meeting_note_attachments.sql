CREATE TABLE tb_meeting_note_attachments
(
    note_id BIGINT NOT NULL,
    position INT NOT NULL,
    object_key VARCHAR(200) NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (note_id, position),
    CONSTRAINT fk_tb_meeting_note_attachments_note FOREIGN KEY (note_id) REFERENCES tb_meeting_notes (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_meeting_note_attachment_states
(
    note_id BIGINT NOT NULL PRIMARY KEY COMMENT '회의록 삭제 후에도 발행 상태를 보존합니다.',
    channel_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    attachments_json JSON NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    state_occurred_at DATETIME(6) NOT NULL,
    INDEX idx_tb_meeting_note_attachment_states_channel_id (channel_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_meeting_note_attachment_states (note_id, channel_id, author_id, attachments_json, deleted, state_occurred_at)
SELECT id, channel_id, created_by, JSON_ARRAY(), FALSE, UTC_TIMESTAMP(6) FROM tb_meeting_notes;
