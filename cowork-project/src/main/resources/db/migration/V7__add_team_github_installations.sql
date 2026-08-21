CREATE TABLE tb_team_github_installations
(
    team_id         BIGINT      NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    installation_id BIGINT      NOT NULL,
    org_login       VARCHAR(255) NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id),
    UNIQUE INDEX uq_tb_team_github_installations_installation_id (installation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
