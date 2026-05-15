CREATE TABLE tb_meeting_notes
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id  BIGINT       NOT NULL COMMENT 'tb_channels.id',
    template_id BIGINT       NOT NULL COMMENT 'tb_meeting_note_templates.id',
    title       VARCHAR(200) NOT NULL,
    content     JSON         NOT NULL COMMENT '섹션별 작성 내용 JSON 블록',
    created_by  BIGINT       NOT NULL COMMENT 'cowork-user의 tb_users.id',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_notes_channel_id (channel_id),
    INDEX idx_tb_meeting_notes_template_id (template_id),
    CONSTRAINT fk_tb_meeting_notes_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_tb_meeting_notes_template FOREIGN KEY (template_id) REFERENCES tb_meeting_note_templates (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
