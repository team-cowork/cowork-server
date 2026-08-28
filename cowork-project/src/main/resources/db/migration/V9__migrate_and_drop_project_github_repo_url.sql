INSERT IGNORE INTO tb_project_github_repos (project_id, team_id, github_repo_url, created_at, updated_at)
SELECT id, team_id, github_repo_url, NOW(6), NOW(6)
FROM tb_projects
WHERE github_repo_url IS NOT NULL;

ALTER TABLE tb_projects DROP COLUMN github_repo_url;
