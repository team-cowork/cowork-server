ALTER TABLE tb_github_issue_write_operations
    ADD COLUMN owner       VARCHAR(255) NOT NULL COMMENT 'GitHub organization/user login' AFTER repo_id,
    ADD COLUMN repo        VARCHAR(255) NOT NULL COMMENT 'GitHub repository name' AFTER owner,
    ADD COLUMN parent_type VARCHAR(20)  NULL COMMENT 'CREATE_COMMENT 전용: ISSUE 또는 PULL_REQUEST' AFTER comment_id,
    ADD CONSTRAINT ck_tb_github_issue_write_operations_parent_type CHECK (
        parent_type IS NULL OR parent_type IN ('ISSUE', 'PULL_REQUEST')
    );
