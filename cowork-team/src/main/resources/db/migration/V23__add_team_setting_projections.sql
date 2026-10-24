CREATE TABLE tb_team_setting_projections
(
    team_id                  BIGINT      NOT NULL COMMENT 'cowork-preference 팀 설정 state의 teamId(cowork-team의 tb_teams.id)',
    nickname_format_enforced BOOLEAN     NOT NULL DEFAULT FALSE,
    nickname_format_example  TEXT        NULL,
    source_occurred_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
