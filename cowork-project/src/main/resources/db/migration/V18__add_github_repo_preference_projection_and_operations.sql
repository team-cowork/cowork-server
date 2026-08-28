CREATE TABLE tb_github_repo_preference_projections
(
    repo_id            BIGINT      NOT NULL COMMENT 'cowork-project의 tb_project_github_repos.id',
    label_auto_apply   BOOLEAN     NOT NULL DEFAULT TRUE,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_tb_github_repo_preference_projections PRIMARY KEY (repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_repo_setting_operations
(
    operation_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key     VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    project_id          BIGINT       NOT NULL COMMENT 'tb_projects.id',
    repo_id             BIGINT       NOT NULL COMMENT 'tb_project_github_repos.id',
    requested_by        BIGINT       NOT NULL COMMENT 'cowork-user id',
    requested_auto_apply BOOLEAN     NOT NULL,
    status              VARCHAR(16)  NOT NULL COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED',
    result_auto_apply   BOOLEAN      NULL,
    expected_state_occurred_at DATETIME(6) NULL,
    error_code          VARCHAR(100) NULL,
    error_message       VARCHAR(500) NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_github_repo_setting_operations PRIMARY KEY (operation_id),
    CONSTRAINT uq_tb_github_repo_setting_operations_requester_key UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_tb_github_repo_setting_operations_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_tb_github_repo_setting_operations_result CHECK (
        (status = 'PENDING' AND result_auto_apply IS NULL AND expected_state_occurred_at IS NULL AND error_code IS NULL AND error_message IS NULL)
        OR (status IN ('PROCESSING', 'SUCCEEDED') AND result_auto_apply IS NOT NULL AND expected_state_occurred_at IS NOT NULL AND error_code IS NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND result_auto_apply IS NULL AND expected_state_occurred_at IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)
    ),
    INDEX idx_tb_github_repo_setting_operations_repo_id (repo_id),
    INDEX idx_tb_github_repo_setting_operations_project_id (project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
