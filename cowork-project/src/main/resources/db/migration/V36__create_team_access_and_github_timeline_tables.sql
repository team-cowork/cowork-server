CREATE TABLE tb_team_access_projections (
    team_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.team_id',
    account_id BIGINT NOT NULL COMMENT 'cowork-preference.tb_team_access_states.account_id',
    allowed_permissions JSON NOT NULL,
    source_membership_version DATETIME(6) NOT NULL,
    source_authorization_version DATETIME(6),
    source_occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id, account_id)
);

CREATE TABLE tb_github_timeline_snapshots
(
    subject      VARCHAR(255) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    snapshot_id  CHAR(36) CHARACTER SET ascii NOT NULL,
    parent_type  VARCHAR(20) NOT NULL,
    item_count   INT NOT NULL,
    completed_at DATETIME(6) NOT NULL
);

CREATE TABLE tb_github_timeline_items
(
    subject       VARCHAR(255) CHARACTER SET ascii NOT NULL,
    snapshot_id   CHAR(36) CHARACTER SET ascii NOT NULL,
    activity_id   VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    payload       JSON NOT NULL,
    PRIMARY KEY (subject, snapshot_id, activity_id),
    INDEX idx_tb_github_timeline_items_time (subject, snapshot_id, occurred_at_ms, activity_id)
);
