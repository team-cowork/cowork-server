ALTER TABLE tb_accounts
    ADD COLUMN datagsm_updated_at DATETIME(6) NULL
        COMMENT '마지막으로 반영한 DataGSM student 이벤트 발생 시각';
