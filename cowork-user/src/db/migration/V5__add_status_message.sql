ALTER TABLE tb_accounts
    ADD COLUMN status_message    VARCHAR(100) NULL AFTER status,
    ADD COLUMN status_expires_at DATETIME(6)  NULL AFTER status_message;
