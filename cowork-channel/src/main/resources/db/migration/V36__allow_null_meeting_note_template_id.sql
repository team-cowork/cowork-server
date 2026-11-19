-- 템플릿 없이 작성한 회의록은 template_id가 NULL입니다. NULL이면 fk_tb_meeting_notes_template 검사를 하지 않습니다.
ALTER TABLE tb_meeting_notes
    MODIFY COLUMN template_id BIGINT NULL COMMENT 'tb_meeting_note_templates.id. 템플릿 없이 작성하면 NULL';
