CREATE TABLE tb_project_access_projections (
    access_key VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id BIGINT NOT NULL COMMENT 'cowork-project.tb_projects.id',
    user_id BIGINT NOT NULL COMMENT 'cowork-user.accounts.id',
    can_read BOOLEAN NOT NULL,
    can_write BOOLEAN NOT NULL,
    source_occurred_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_tb_project_access_projections_project_user (project_id, user_id)
);
