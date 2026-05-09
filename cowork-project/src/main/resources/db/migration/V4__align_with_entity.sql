DROP TABLE IF EXISTS tb_project_roles;

ALTER TABLE tb_projects
    ADD COLUMN description VARCHAR(500) NULL AFTER name,
    ADD COLUMN status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED' AFTER description,
    ADD COLUMN created_by  BIGINT       NOT NULL DEFAULT 0 COMMENT 'cowork-user의 tb_users.id' AFTER status,
    ADD INDEX idx_tb_projects_created_by (created_by);

CREATE TABLE tb_project_members
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL COMMENT 'cowork-user의 tb_users.id',
    role       VARCHAR(20) NOT NULL COMMENT 'OWNER, EDITOR, VIEWER',
    joined_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tb_project_members_project_id_user_id UNIQUE (project_id, user_id),
    INDEX idx_tb_project_members_user_id (user_id),
    CONSTRAINT fk_tb_project_members_project FOREIGN KEY (project_id) REFERENCES tb_projects (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
