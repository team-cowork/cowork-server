ALTER TABLE tb_projects
    ADD COLUMN github_webhook_channel_id BIGINT DEFAULT NULL COMMENT 'cowork-channel의 tb_channels.id, GitHub 알림 수신 채널',
    ADD UNIQUE INDEX uq_tb_projects_team_id_github_repo_url (team_id, github_repo_url);
