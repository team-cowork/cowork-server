CREATE TABLE tb_users
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    datagsm_id        BIGINT       NOT NULL COMMENT 'DataGSM 계정 ID',
    name              VARCHAR(50)  NOT NULL,
    email             VARCHAR(255) NOT NULL,
    sex               VARCHAR(10)  NOT NULL COMMENT 'MAN, WOMAN',
    grade             INT                   DEFAULT NULL,
    class_num         INT                   DEFAULT NULL,
    student_number    INT                   DEFAULT NULL,
    major             VARCHAR(30)           DEFAULT NULL,
    student_role      VARCHAR(30)           DEFAULT NULL,
    github_id         VARCHAR(100)          DEFAULT NULL,
    role              VARCHAR(20)  NOT NULL DEFAULT 'MEMBER' COMMENT 'MEMBER, ADMIN',
    nickname          VARCHAR(50)           DEFAULT NULL,
    description       VARCHAR(500)          DEFAULT NULL,
    specialty         VARCHAR(255)          DEFAULT NULL,
    profile_image_key VARCHAR(500)          DEFAULT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_users_datagsm_id (datagsm_id),
    UNIQUE KEY uq_tb_users_email (email),
    INDEX idx_tb_users_major_grade (major, grade)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_refresh_tokens
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  CHAR(64)     NOT NULL COMMENT 'SHA-256 hex',
    expires_at  DATETIME(6)  NOT NULL,
    device_info VARCHAR(255)          DEFAULT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_refresh_tokens_token_hash (token_hash),
    INDEX idx_tb_refresh_tokens_user_id (user_id),
    INDEX idx_tb_refresh_tokens_expires_at (expires_at),
    CONSTRAINT fk_tb_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
