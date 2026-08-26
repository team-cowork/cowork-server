ALTER TABLE tb_team_memberships
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN source_occurred_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00';

CREATE INDEX idx_tb_team_memberships_team_id_active
    ON tb_team_memberships (team_id, active);
