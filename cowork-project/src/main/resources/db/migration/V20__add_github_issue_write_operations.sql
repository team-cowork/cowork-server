CREATE TABLE tb_github_issue_write_operations
(
    operation_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_type    VARCHAR(20)  NOT NULL COMMENT 'REPLACE_LABELS, CREATE_COMMENT, UPDATE_COMMENT, DELETE_COMMENT',
    repo_id         BIGINT       NOT NULL COMMENT 'tb_project_github_repos.id',
    issue_number    INT          NULL,
    comment_id      BIGINT       NULL,
    requested_by    BIGINT       NOT NULL COMMENT 'cowork-user id',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING, SUCCEEDED, FAILED',
    result_snapshot TEXT         NULL,
    error_code      VARCHAR(100) NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_github_issue_write_operations PRIMARY KEY (operation_id),
    CONSTRAINT uq_tb_github_issue_write_operations_requester_key UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_tb_github_issue_write_operations_command_type CHECK (
        command_type IN ('REPLACE_LABELS', 'CREATE_COMMENT', 'UPDATE_COMMENT', 'DELETE_COMMENT')
    ),
    CONSTRAINT ck_tb_github_issue_write_operations_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    INDEX idx_tb_github_issue_write_operations_repo_id (repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
