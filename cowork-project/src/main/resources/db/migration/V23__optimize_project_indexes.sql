ALTER TABLE tb_project_github_repos
    ADD INDEX idx_tb_project_github_repos_webhook_channel_id (github_webhook_channel_id),
    DROP INDEX idx_tb_project_github_repos_github_repo_url;

ALTER TABLE tb_kafka_outbox
    ADD INDEX idx_tb_kafka_outbox_status_id (status, id),
    DROP INDEX idx_tb_kafka_outbox_id;

ALTER TABLE tb_projects
    DROP INDEX idx_tb_projects_team_id_name,
    DROP INDEX idx_tb_projects_created_by;

ALTER TABLE tb_github_repo_setting_operations
    ADD INDEX idx_tb_github_repo_setting_operations_repo_id_status_created_at (repo_id, status, created_at),
    DROP INDEX idx_tb_github_repo_setting_operations_repo_id;
