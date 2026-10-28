ALTER TABLE tb_github_issue_projections
    DROP INDEX idx_tb_github_issue_projections_owner_repo,
    ADD INDEX idx_tb_github_issue_projections_owner_repo_number (owner, repo, number);

ALTER TABLE tb_github_pull_request_projections
    DROP INDEX idx_tb_github_pull_request_projections_owner_repo,
    ADD INDEX idx_tb_github_pull_request_projections_owner_repo_number (owner, repo, number);
