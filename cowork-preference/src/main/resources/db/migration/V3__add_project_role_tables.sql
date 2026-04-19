CREATE TABLE project_role_definition
(
    project_id  BIGINT      NOT NULL,
    role_name   VARCHAR(50) NOT NULL,
    permissions JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, role_name)
);

CREATE TABLE account_project_role
(
    account_id BIGINT      NOT NULL,
    project_id BIGINT      NOT NULL,
    role_name  VARCHAR(50) NOT NULL,
    PRIMARY KEY (account_id, project_id, role_name),
    CONSTRAINT fk_apr_role FOREIGN KEY (project_id, role_name)
        REFERENCES project_role_definition (project_id, role_name) ON DELETE CASCADE
);

CREATE INDEX idx_apr_account_id ON account_project_role (account_id);
CREATE INDEX idx_apr_project_id ON account_project_role (project_id);

CREATE TRIGGER trg_prd_updated_at
    BEFORE UPDATE ON project_role_definition
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
