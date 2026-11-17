ALTER TABLE tb_project_access_projections
    ADD COLUMN can_board_read BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'GitHub Project 보드 보기 권한: cowork-preference.tb_project_access_states.can_board_read',
    ADD COLUMN can_board_write BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'GitHub Project 보드 카드 이동 권한: cowork-preference.tb_project_access_states.can_board_write';
