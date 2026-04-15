CREATE TABLE tb_teams
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(512),
    description VARCHAR(500),
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'public' COMMENT 'public, private',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_tb_teams_visibility_id (visibility, id),
    INDEX idx_tb_teams_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
