CREATE TABLE tb_team_github_installation_event_states
(
    team_id            BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    installation_id    BIGINT       NULL,
    org_login          VARCHAR(255) NULL,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (team_id),
    INDEX idx_tb_team_github_installation_event_states_installation_id (installation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_github_installation_ownership_fences
(
    installation_id    BIGINT      NOT NULL,
    owner_team_id      BIGINT      NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    active             BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (installation_id),
    INDEX idx_tb_github_installation_ownership_fences_owner_team_id (owner_team_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_team_github_installation_event_states (
    team_id,
    installation_id,
    org_login,
    deleted,
    source_occurred_at
)
SELECT team_id,
       installation_id,
       org_login,
       FALSE,
       '1970-01-01 00:00:00.000000'
FROM tb_team_github_installations;

INSERT INTO tb_github_installation_ownership_fences (
    installation_id,
    owner_team_id,
    active,
    source_occurred_at
)
SELECT installation_id,
       team_id,
       TRUE,
       '1970-01-01 00:00:00.000000'
FROM tb_team_github_installations;
