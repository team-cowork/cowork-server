CREATE TABLE tb_github_repo_projections
(
    id                 VARCHAR(300) NOT NULL COMMENT '"owner/repo"',
    installation_id    BIGINT       NOT NULL,
    owner              VARCHAR(100) NOT NULL,
    repo               VARCHAR(100) NOT NULL,
    full_name          VARCHAR(201) NOT NULL,
    is_private         BOOLEAN      NOT NULL,
    html_url           VARCHAR(512) NOT NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_repo_projections PRIMARY KEY (id),
    INDEX idx_tb_github_repo_projections_owner (owner)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_org_backfill_statuses
(
    org_login    VARCHAR(100) NOT NULL,
    completed_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_org_backfill_statuses PRIMARY KEY (org_login)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_issue_projections
(
    id                 VARCHAR(300) NOT NULL COMMENT '"owner/repo#number"',
    owner              VARCHAR(100) NOT NULL,
    repo               VARCHAR(100) NOT NULL,
    number             INT          NOT NULL,
    title              VARCHAR(500) NOT NULL,
    author             VARCHAR(100) NOT NULL,
    state              VARCHAR(20)  NOT NULL,
    html_url           VARCHAR(512) NOT NULL,
    github_created_at  VARCHAR(40)  NOT NULL,
    github_updated_at  VARCHAR(40)  NOT NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_issue_projections PRIMARY KEY (id),
    INDEX idx_tb_github_issue_projections_owner_repo (owner, repo)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_issue_projection_labels
(
    issue_id VARCHAR(300) NOT NULL,
    label    VARCHAR(100) NOT NULL,
    CONSTRAINT fk_tb_github_issue_projection_labels_issue
        FOREIGN KEY (issue_id) REFERENCES tb_github_issue_projections (id) ON DELETE CASCADE,
    INDEX idx_tb_github_issue_projection_labels_issue_id (issue_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_pull_request_projections
(
    id                 VARCHAR(300) NOT NULL COMMENT '"owner/repo#number"',
    owner              VARCHAR(100) NOT NULL,
    repo               VARCHAR(100) NOT NULL,
    number             INT          NOT NULL,
    title              VARCHAR(500) NOT NULL,
    body               TEXT         NULL,
    author             VARCHAR(100) NOT NULL,
    state              VARCHAR(20)  NOT NULL,
    draft              BOOLEAN      NOT NULL,
    merged             BOOLEAN      NOT NULL,
    html_url           VARCHAR(512) NOT NULL,
    head_ref           VARCHAR(300) NOT NULL,
    base_ref           VARCHAR(300) NOT NULL,
    github_created_at  VARCHAR(40)  NOT NULL,
    github_updated_at  VARCHAR(40)  NOT NULL,
    files_source_occurred_at DATETIME(6) NOT NULL,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_pull_request_projections PRIMARY KEY (id),
    INDEX idx_tb_github_pull_request_projections_owner_repo (owner, repo)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_pr_projection_labels
(
    pr_id VARCHAR(300) NOT NULL,
    label VARCHAR(100) NOT NULL,
    CONSTRAINT fk_tb_github_pr_projection_labels_pr
        FOREIGN KEY (pr_id) REFERENCES tb_github_pull_request_projections (id) ON DELETE CASCADE,
    INDEX idx_tb_github_pr_projection_labels_pr_id (pr_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_pr_projection_files
(
    pr_id     VARCHAR(300)  NOT NULL,
    filename  VARCHAR(1024) NOT NULL,
    status    VARCHAR(20)   NOT NULL,
    additions INT           NOT NULL,
    deletions INT           NOT NULL,
    patch     LONGTEXT      NULL,
    CONSTRAINT fk_tb_github_pr_projection_files_pr
        FOREIGN KEY (pr_id) REFERENCES tb_github_pull_request_projections (id) ON DELETE CASCADE,
    INDEX idx_tb_github_pr_projection_files_pr_id (pr_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_label_projections
(
    id                 VARCHAR(300) NOT NULL COMMENT '"owner/repo:name"',
    owner              VARCHAR(100) NOT NULL,
    repo               VARCHAR(100) NOT NULL,
    name               VARCHAR(100) NOT NULL,
    color              VARCHAR(10)  NOT NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_label_projections PRIMARY KEY (id),
    INDEX idx_tb_github_label_projections_owner_repo (owner, repo)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_issue_comment_projections
(
    id                 VARCHAR(300) NOT NULL COMMENT '"owner/repo#commentId"',
    owner              VARCHAR(100) NOT NULL,
    repo               VARCHAR(100) NOT NULL,
    comment_id         BIGINT       NOT NULL,
    parent_type        VARCHAR(20)  NOT NULL COMMENT 'ISSUE, PULL_REQUEST',
    parent_number      INT          NOT NULL,
    author             VARCHAR(100) NOT NULL,
    body               TEXT         NOT NULL,
    html_url           VARCHAR(512) NOT NULL,
    github_created_at  VARCHAR(40)  NOT NULL,
    github_updated_at  VARCHAR(40)  NOT NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_tb_github_issue_comment_projections PRIMARY KEY (id),
    INDEX idx_tb_github_issue_comment_projections_owner_repo_parent (owner, repo, parent_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
