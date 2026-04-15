CREATE TABLE tb_accounts
(
    id          BIGINT       NOT NULL COMMENT 'authorization 서비스의 tb_users.id와 동일',
    name        VARCHAR(50)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    sex         VARCHAR(10)  NOT NULL COMMENT 'MAN, WOMAN',
    github      VARCHAR(100)          DEFAULT NULL,
    description VARCHAR(500)          DEFAULT NULL,
    st_role     VARCHAR(50)           DEFAULT NULL,
    st_num      VARCHAR(30)           DEFAULT NULL,
    major       VARCHAR(50)           DEFAULT NULL,
    spe         VARCHAR(255)          DEFAULT NULL,
    status      VARCHAR(30)  NOT NULL DEFAULT 'offline',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_accounts_email (email),
    UNIQUE KEY uq_tb_accounts_github (github),
    INDEX idx_tb_accounts_st_num (st_num),
    INDEX idx_tb_accounts_status_major_st_role_id (status, major, st_role, id),
    INDEX idx_tb_accounts_major_st_role_id (major, st_role, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_profiles
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    img         VARCHAR(500)          DEFAULT NULL COMMENT 'S3 object key (profiles/{userId}/{uuid}.ext)',
    nickname    VARCHAR(50)           DEFAULT NULL,
    description VARCHAR(500)          DEFAULT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_profiles_account_id (account_id),
    INDEX idx_tb_profiles_nickname_account_id (nickname, account_id),
    CONSTRAINT fk_tb_profiles_account FOREIGN KEY (account_id) REFERENCES tb_accounts (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_profile_roles
(
    profile_id BIGINT      NOT NULL,
    role       VARCHAR(50) NOT NULL,
    PRIMARY KEY (profile_id, role),
    INDEX idx_tb_profile_roles_role_profile_id (role, profile_id),
    CONSTRAINT fk_tb_profile_roles_profile FOREIGN KEY (profile_id) REFERENCES tb_profiles (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_accounts (
    id,
    name,
    email,
    sex,
    github,
    description,
    st_role,
    st_num,
    major,
    spe,
    status,
    created_at,
    updated_at
)
SELECT
    id,
    name,
    email,
    sex,
    github_id,
    NULL,
    role,
    NULL,
    major,
    specialty,
    'offline',
    created_at,
    updated_at
FROM tb_user_profiles;

INSERT INTO tb_profiles (
    account_id,
    img,
    nickname,
    description,
    created_at,
    updated_at
)
SELECT
    id,
    profile_image_key,
    NULL,
    NULL,
    created_at,
    updated_at
FROM tb_user_profiles;

INSERT INTO tb_profile_roles (profile_id, role)
SELECT p.id, u.role
FROM tb_profiles p
JOIN tb_user_profiles u ON u.id = p.account_id;

DROP TABLE tb_user_profiles;
