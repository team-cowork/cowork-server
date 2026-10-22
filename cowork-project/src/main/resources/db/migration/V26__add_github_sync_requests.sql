CREATE TABLE tb_github_sync_requests
(
    id           VARCHAR(300) NOT NULL COMMENT '"REPOSITORY:owner/repo" 또는 "ORG_REPOSITORIES:owner"',
    requested_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_sync_requests PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
