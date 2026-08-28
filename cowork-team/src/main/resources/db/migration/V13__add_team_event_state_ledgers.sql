CREATE TABLE tb_team_event_states
(
    team_id                BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id; 삭제 후에도 유지',
    name                   VARCHAR(100) NOT NULL,
    description            VARCHAR(500) NULL,
    icon_url               VARCHAR(512) NULL,
    owner_id               BIGINT       NOT NULL COMMENT 'cowork-user의 account id',
    github_installation_id BIGINT       NULL,
    github_org_login       VARCHAR(255) NULL,
    actor_user_id          BIGINT       NOT NULL COMMENT '마지막 상태 변경 actor의 cowork-user account id',
    deleted                BOOLEAN      NOT NULL DEFAULT FALSE,
    state_occurred_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (team_id),
    INDEX idx_tb_team_event_states_deleted_team_id (deleted, team_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_team_event_states
    (team_id, name, description, icon_url, owner_id, github_installation_id,
     github_org_login, actor_user_id, deleted, state_occurred_at)
SELECT id,
       name,
       description,
       icon_url,
       owner_id,
       github_installation_id,
       github_org_login,
       COALESCE(last_modified_by, created_by, owner_id),
       FALSE,
       GREATEST(created_at, updated_at)
FROM tb_teams;

CREATE TABLE tb_team_member_event_states
(
    team_id           BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id; 삭제 후에도 유지',
    user_id           BIGINT       NOT NULL COMMENT 'cowork-user의 account id',
    role              VARCHAR(20)  NOT NULL,
    team_name         VARCHAR(100) NOT NULL,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    state_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (team_id, user_id),
    INDEX idx_tb_team_member_event_states_user_id_deleted (user_id, deleted),
    CONSTRAINT ck_tb_team_member_event_states_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_team_member_event_states
    (team_id, user_id, role, team_name, deleted, state_occurred_at)
SELECT member.team_id,
       member.user_id,
       member.role,
       team.name,
       FALSE,
       GREATEST(member.joined_at, member.updated_at, team.created_at, team.updated_at)
FROM tb_team_members member
JOIN tb_teams team ON team.id = member.team_id;

