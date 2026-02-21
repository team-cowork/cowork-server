CREATE TABLE tb_meeting_note_templates
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_note_templates_channel_active (channel_id, is_active),
    CONSTRAINT fk_tb_meeting_note_templates_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_meeting_note_template_sections
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    template_id BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'TEXT, MARKDOWN, DATE, DATETIME, USER_LIST',
    placeholder VARCHAR(255)          DEFAULT NULL,
    required    BOOLEAN      NOT NULL DEFAULT FALSE,
    position    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_note_template_sections_template_position (template_id, position),
    CONSTRAINT fk_tb_meeting_note_template_sections_template FOREIGN KEY (template_id) REFERENCES tb_meeting_note_templates (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_meeting_notes
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id  BIGINT       NOT NULL,
    template_id BIGINT       NOT NULL COMMENT '작성 당시 활성 템플릿 (삭제되어도 회의록은 유지)',
    title       VARCHAR(200) NOT NULL,
    content     JSON         NOT NULL COMMENT '섹션 스냅샷 배열',
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_notes_channel_id_id (channel_id, id),
    CONSTRAINT fk_tb_meeting_notes_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
