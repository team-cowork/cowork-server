-- 기존 channel 인스턴스가 동시에 쓰면 원본 row와 event state가 갈라질 수 있으므로 모두 중지한 상태에서 적용한다.
-- 빈 DB의 첫 배포에서는 아래 INSERT SELECT가 0건으로 끝난다.
CREATE TABLE tb_channel_event_states
(
    channel_id        BIGINT       NOT NULL COMMENT 'cowork-channel의 tb_channels.id; 삭제 후에도 유지',
    team_id           BIGINT       NULL COMMENT 'cowork-team의 tb_teams.id',
    project_id        BIGINT       NULL COMMENT 'cowork-project의 tb_projects.id',
    name              VARCHAR(100) NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    view_type         VARCHAR(30)  NOT NULL,
    description       VARCHAR(500) NULL,
    is_private        BOOLEAN      NOT NULL,
    position          INT          NOT NULL,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    state_occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (channel_id),
    INDEX idx_tb_channel_event_states_deleted_channel_id (deleted, channel_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_channel_event_states
    (channel_id, team_id, project_id, name, type, view_type, description,
     is_private, position, deleted, state_occurred_at)
SELECT id,
       team_id,
       project_id,
       name,
       type,
       view_type,
       description,
       is_private,
       position,
       FALSE,
       GREATEST(updated_at, CURRENT_TIMESTAMP(6))
FROM tb_channels;

CREATE TABLE tb_channel_member_event_states
(
    channel_id        BIGINT      NOT NULL COMMENT 'cowork-channel의 tb_channels.id; 삭제 후에도 유지',
    user_id           BIGINT      NOT NULL COMMENT 'cowork-user의 tb_users.id',
    team_id           BIGINT      NULL COMMENT 'cowork-team의 tb_teams.id',
    role              VARCHAR(20) NOT NULL,
    channel_type      VARCHAR(20) NOT NULL,
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    state_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (channel_id, user_id),
    INDEX idx_tb_channel_member_event_states_user_id_deleted (user_id, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_channel_member_event_states
    (channel_id, user_id, team_id, role, channel_type, deleted, state_occurred_at)
SELECT member.channel_id,
       member.user_id,
       channel.team_id,
       'MEMBER',
       channel.type,
       FALSE,
       GREATEST(member.joined_at, CURRENT_TIMESTAMP(6))
FROM tb_channel_members member
JOIN tb_channels channel ON channel.id = member.channel_id;
