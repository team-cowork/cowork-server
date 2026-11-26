ALTER TABLE tb_team_member_projections
    ADD COLUMN authorization_version DATETIME(6) COMMENT 'cowork-team.tb_team_member_event_states.authorization_version';

CREATE TABLE tb_team_access_projections (
    team_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.team_id',
    account_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.account_id',
    manage_roadmaps BOOLEAN NOT NULL,
    source_membership_version DATETIME(6) NOT NULL,
    source_authorization_version DATETIME(6),
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id, account_id)
);
