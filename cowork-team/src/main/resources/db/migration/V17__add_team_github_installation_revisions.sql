CREATE TABLE tb_team_github_installation_revisions
(
    installation_id BIGINT      NOT NULL COMMENT '외부 cowork-github-app installation ID',
    revision        BIGINT      NOT NULL DEFAULT 0,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (installation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
