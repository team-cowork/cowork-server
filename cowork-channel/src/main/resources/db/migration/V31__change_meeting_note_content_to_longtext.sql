ALTER TABLE tb_meeting_notes
    MODIFY COLUMN content LONGTEXT NOT NULL COMMENT '섹션별 작성 내용 JSON 객체 문자열 (키 순서 보존)';
