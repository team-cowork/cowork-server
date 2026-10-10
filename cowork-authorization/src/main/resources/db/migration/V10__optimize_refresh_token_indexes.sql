-- token_hash는 항상 SHA-256 lowercase hex(64자)이므로 고정 길이 ascii 컬럼으로 축소
-- 세션 수 집계(user_id = ? AND expires_at > ?)와 만료 정리(user_id IN ? AND expires_at <= ?)는
-- 복합 인덱스로 처리하고, 같은 leading column을 가진 단일 user_id 인덱스는 제거
ALTER TABLE tb_refresh_tokens
    MODIFY COLUMN token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD INDEX idx_tb_refresh_tokens_user_id_expires_at (user_id, expires_at),
    DROP INDEX idx_tb_refresh_tokens_user_id;
