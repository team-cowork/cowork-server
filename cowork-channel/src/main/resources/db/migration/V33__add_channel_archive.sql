CREATE TABLE tb_project_access_projections
(
    access_key                   VARCHAR(50) NOT NULL COMMENT '{project_id}:{user_id}',
    project_id                   BIGINT      NOT NULL COMMENT 'cowork-project의 tb_projects.id',
    user_id                      BIGINT      NOT NULL COMMENT 'cowork-user의 account id',
    can_read                     BOOLEAN     NOT NULL,
    can_channel_archive          BOOLEAN     NOT NULL,
    source_occurred_at           DATETIME(6) NOT NULL,
    source_membership_version    DATETIME(6) NOT NULL,
    source_authorization_version DATETIME(6) NULL,
    PRIMARY KEY (access_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = 'cowork-preference의 preference.project-access.state.v1 projection';

ALTER TABLE tb_channels
    ADD COLUMN archived_at DATETIME(6) NULL COMMENT '보관 시각. NULL이면 활성 채널',
    ADD COLUMN archived_by BIGINT      NULL COMMENT '보관한 사용자. cowork-user의 account id';

ALTER TABLE tb_channel_event_states
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'channel.event.v2로 발행한 보관 상태';
