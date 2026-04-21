-- tb_refresh_tokens에서 tb_users 참조 FK 제거
ALTER TABLE tb_refresh_tokens
    DROP FOREIGN KEY fk_tb_refresh_tokens_user;


-- tb_users 제거
DROP TABLE IF EXISTS tb_users;

-- tb_refresh_tokens에 JWT 클레임 컬럼 추가 (토큰 갱신 시 user 서비스 호출 없이 재발급 가능하도록)
ALTER TABLE tb_refresh_tokens
    ADD COLUMN email    VARCHAR(255) NOT NULL DEFAULT '' AFTER token_hash,
    ADD COLUMN gsm_role VARCHAR(30)  NOT NULL DEFAULT '' AFTER email;
``

-- tb_oauth2_connections 제거 (tb_users 참조 의존성 제거)
DROP TABLE IF EXISTS tb_oauth2_connections;
