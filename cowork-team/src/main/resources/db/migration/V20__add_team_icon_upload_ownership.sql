CREATE TABLE tb_team_icon_uploads
(
    object_key   VARCHAR(255) NOT NULL,
    upload_key   VARCHAR(255) NOT NULL,
    uploaded_by  BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    content_type VARCHAR(100) NOT NULL,
    confirmed    BOOLEAN      NOT NULL DEFAULT FALSE,
    team_id      BIGINT       NULL COMMENT '아이콘을 연결한 tb_teams.id. 팀 삭제 후에도 재사용을 막기 위해 소유 기록을 유지',
    retired      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (object_key),
    UNIQUE KEY uq_tb_team_icon_uploads_upload_key (upload_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
