-- DataGSM 계정 역할(USER/ADMIN)을 플랫폼 역할로 반영하기 위해 리프레시 토큰에 role 컬럼 추가
-- (토큰 갱신 시 DataGSM 재조회 없이 재발급 가능하도록 gsm_role과 동일한 방식으로 보관)
ALTER TABLE tb_refresh_tokens
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'ADMIN, MEMBER' AFTER email;
