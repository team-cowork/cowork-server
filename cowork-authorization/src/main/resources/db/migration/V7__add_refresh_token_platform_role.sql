ALTER TABLE tb_refresh_tokens
    ADD COLUMN platform_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' AFTER gsm_role;
