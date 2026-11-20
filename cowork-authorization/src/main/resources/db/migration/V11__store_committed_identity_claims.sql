ALTER TABLE tb_user_identity_operations
    MODIFY user_id BIGINT NULL COMMENT '기존 v2 명령의 cowork-user.tb_accounts.id',
    ADD result_email VARCHAR(255) NULL,
    ADD result_gsm_role VARCHAR(50) NULL;
