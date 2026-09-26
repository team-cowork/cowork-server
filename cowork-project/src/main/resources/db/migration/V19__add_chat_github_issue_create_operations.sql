CREATE TABLE tb_chat_github_issue_create_operations
(
    operation_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id      BIGINT       NOT NULL COMMENT 'tb_projects.id',
    requester_id    BIGINT       NOT NULL COMMENT 'cowork-user id',
    channel_id      BIGINT       NOT NULL COMMENT 'cowork-chat 채널 id',
    team_id         BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING, ACCEPTED, REJECTED',
    error_code      VARCHAR(100) NULL,
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_tb_chat_github_issue_create_operations PRIMARY KEY (operation_id),
    CONSTRAINT uq_tb_chat_github_issue_create_operations_requester_key UNIQUE (requester_id, idempotency_key),
    CONSTRAINT ck_tb_chat_github_issue_create_operations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    INDEX idx_tb_chat_github_issue_create_operations_project_id (project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
