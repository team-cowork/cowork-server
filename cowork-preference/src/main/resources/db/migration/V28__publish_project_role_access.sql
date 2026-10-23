ALTER TABLE project_role_definition ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE TABLE tb_project_access_states (
    project_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    can_read BOOLEAN NOT NULL,
    can_write BOOLEAN NOT NULL,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, account_id)
);
COMMENT ON COLUMN tb_project_access_states.project_id IS 'cowork-project.tb_projects.id';
COMMENT ON COLUMN tb_project_access_states.account_id IS 'cowork-user.accounts.id';

ALTER TABLE tb_project_lifecycle_projections ADD COLUMN created_by BIGINT NULL;
COMMENT ON COLUMN tb_project_lifecycle_projections.created_by IS 'cowork-user.accounts.id';

ALTER TABLE tb_channel_lifecycle_projections ADD COLUMN channel_type VARCHAR(30) NULL;
