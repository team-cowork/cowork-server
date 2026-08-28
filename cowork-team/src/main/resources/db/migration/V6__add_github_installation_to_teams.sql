ALTER TABLE tb_teams
    ADD COLUMN github_installation_id BIGINT DEFAULT NULL COMMENT 'GitHub App installation ID',
    ADD COLUMN github_org_login VARCHAR(255) DEFAULT NULL COMMENT 'GitHub App가 설치된 조직 로그인명';
