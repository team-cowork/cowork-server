ALTER TABLE tb_project_github_repos
    ADD COLUMN state_occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT 'project.github-repo.event LWW version' AFTER github_webhook_channel_id;

ALTER TABLE tb_projects
    ADD COLUMN state_occurred_at DATETIME(6) NULL
        COMMENT 'project.event authoritative monotonic version' AFTER updated_at;

UPDATE tb_projects
SET state_occurred_at = updated_at,
    updated_at = updated_at;

ALTER TABLE tb_projects
    MODIFY COLUMN state_occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT 'project.event authoritative monotonic version';

ALTER TABLE tb_project_members
    ADD COLUMN state_occurred_at DATETIME(6) NULL
        COMMENT 'project.member.event authoritative monotonic version' AFTER joined_at;

UPDATE tb_project_members
SET state_occurred_at = joined_at;

ALTER TABLE tb_project_members
    MODIFY COLUMN state_occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT 'project.member.event authoritative monotonic version';

CREATE TABLE tb_project_event_tombstones
(
    project_id        BIGINT       NOT NULL COMMENT 'deleted tb_projects.id',
    team_id           BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    name              VARCHAR(100) NOT NULL,
    description       VARCHAR(500) NULL,
    status            VARCHAR(20)  NOT NULL COMMENT 'ACTIVE, ARCHIVED',
    position          INT          NOT NULL,
    state_occurred_at DATETIME(6)  NOT NULL COMMENT 'latest project.event DELETED version',
    CONSTRAINT pk_tb_project_event_tombstones PRIMARY KEY (project_id),
    INDEX idx_tb_project_event_tombstones_team_id (team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_project_member_event_tombstones
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    project_id        BIGINT      NOT NULL COMMENT 'deleted relation의 tb_projects.id',
    user_id           BIGINT      NOT NULL COMMENT 'deleted relation의 cowork-user id',
    state_occurred_at DATETIME(6) NOT NULL COMMENT 'latest project.member.event REMOVED version',
    CONSTRAINT pk_tb_project_member_event_tombstones PRIMARY KEY (id),
    CONSTRAINT uq_tb_project_member_event_tombstones_project_user UNIQUE (project_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_channel_projections
(
    channel_id         BIGINT      NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
    project_id         BIGINT      NULL COMMENT 'cowork-channel의 tb_channels.project_id',
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (channel_id),
    INDEX idx_tb_channel_projections_project_id (project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE tb_user_profile_projections
(
    user_id            BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    github_id          VARCHAR(255) NULL COMMENT 'cowork-user의 tb_accounts.github',
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    source_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    INDEX idx_tb_user_profile_projections_github_id (github_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
