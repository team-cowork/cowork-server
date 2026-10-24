ALTER TABLE tb_team_role_projections
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_tb_team_role_projections_team_default
    ON tb_team_role_projections (team_id, is_default, deleted);
