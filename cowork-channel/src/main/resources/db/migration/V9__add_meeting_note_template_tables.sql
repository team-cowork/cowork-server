CREATE TABLE tb_meeting_note_templates
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id BIGINT       NOT NULL COMMENT 'tb_channels.id',
    name       VARCHAR(100) NOT NULL,
    is_active  TINYINT(1)   NOT NULL DEFAULT 0,
    created_by BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_note_templates_channel_id (channel_id),
    INDEX idx_tb_meeting_note_templates_channel_id_is_active (channel_id, is_active),
    CONSTRAINT fk_tb_meeting_note_templates_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE
);

CREATE TABLE tb_meeting_note_template_sections
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    template_id BIGINT       NOT NULL COMMENT 'tb_meeting_note_templates.id',
    title       VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'TEXT, MARKDOWN, DATE, DATETIME, USER_LIST',
    placeholder VARCHAR(255),
    is_required TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_meeting_note_template_sections_template_id (template_id),
    CONSTRAINT fk_tb_meeting_note_template_sections_template FOREIGN KEY (template_id) REFERENCES tb_meeting_note_templates (id) ON DELETE CASCADE
);
