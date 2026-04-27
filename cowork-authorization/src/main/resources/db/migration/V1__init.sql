CREATE TABLE tb_users
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'MEMBER' COMMENT 'ADMIN, MEMBER',
    is_active  TINYINT(1)   NOT NULL DEFAULT 1,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_users_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_refresh_tokens
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(512) NOT NULL,
    device_info VARCHAR(255),
    expires_at  DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_refresh_tokens_hash (token_hash),
    INDEX idx_tb_refresh_tokens_user_id (user_id),
    INDEX idx_tb_refresh_tokens_expires_at (expires_at),
    CONSTRAINT fk_tb_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_oauth2_connections
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(50)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_oauth2_provider_user (provider, provider_user_id),
    INDEX idx_tb_oauth2_user_id (user_id),
    CONSTRAINT fk_tb_oauth2_user FOREIGN KEY (user_id) REFERENCES tb_users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
