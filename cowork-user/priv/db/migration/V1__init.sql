-- id는 cowork-authorization의 tb_users.id와 동일한 값 사용 (FK 없음, MSA 원칙)
-- email은 authorization 서비스와 Kafka 이벤트로 동기화
CREATE TABLE tb_user_profiles
(
    id        BIGINT       NOT NULL COMMENT 'authorization 서비스의 tb_users.id와 동일',
    name      VARCHAR(50)  NOT NULL,
    email     VARCHAR(255) NOT NULL,
    sex       VARCHAR(10)  NOT NULL COMMENT 'MAN, WOMAN',
    grade     TINYINT               DEFAULT NULL COMMENT '학년 (1~3). 졸업/자퇴 시 NULL 가능',
    class     TINYINT               DEFAULT NULL COMMENT '반 (1~4). 졸업/자퇴 시 NULL 가능',
    class_num TINYINT               DEFAULT NULL COMMENT '번호. 졸업/자퇴 시 NULL 가능',
    major     VARCHAR(20)  NOT NULL COMMENT 'SW_DEVELOPMENT, SMART_IOT, AI',
    specialty VARCHAR(255)          DEFAULT NULL COMMENT '개인 특기/전문 분야',
    github_id VARCHAR(100)          DEFAULT NULL,
    role      VARCHAR(30)  NOT NULL DEFAULT 'GENERAL_STUDENT'
                           COMMENT 'STUDENT_COUNCIL, DORMITORY_MANAGER, GENERAL_STUDENT, GRADUATE, WITHDRAWN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_user_profiles_email (email),
    UNIQUE KEY uq_tb_user_profiles_github_id (github_id),
    INDEX idx_tb_user_profiles_role (role),
    INDEX idx_tb_user_profiles_major (major),
    INDEX idx_tb_user_profiles_grade_class (grade, class)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
