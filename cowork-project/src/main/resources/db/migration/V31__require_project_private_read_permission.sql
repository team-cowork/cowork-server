ALTER TABLE tb_project_access_projections
    MODIFY COLUMN can_read_private BOOLEAN NOT NULL
    COMMENT '비공개 전환 후 읽기 가능 여부: cowork-preference.tb_project_access_states.can_read_private';
