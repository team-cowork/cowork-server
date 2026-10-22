DROP TABLE tb_github_org_backfill_statuses;

CREATE TABLE tb_github_sync_markers
(
    id           VARCHAR(300) NOT NULL COMMENT '"STREAM:owner/repo", org 단위 repo 목록은 "REPO:owner"',
    completed_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_sync_markers PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
