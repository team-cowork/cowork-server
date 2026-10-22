-- AUTOINCREMENT가 삭제된 id의 재사용을 막아 installation_id가 delivery retry의 소유 세대로 유지된다.
CREATE TABLE tb_device_installation
(
    id         INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER  NOT NULL, -- cowork-user의 tb_accounts.id
    fid        TEXT     NOT NULL, -- Firebase installation ID
    platform   TEXT     NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX uq_tb_device_installation_fid ON tb_device_installation (fid);
CREATE INDEX idx_tb_device_installation_account_id ON tb_device_installation (account_id);

CREATE TABLE tb_notification_delivery_retry
(
    id               INTEGER  NOT NULL PRIMARY KEY AUTOINCREMENT,
    event_id         TEXT     NOT NULL, -- 알림 트리거 이벤트의 안정적 식별자 (notification.trigger의 eventId)
    installation_id  INTEGER  NOT NULL, -- tb_device_installation.id 스냅샷
    fid              TEXT     NOT NULL, -- 전송 시점의 FID 스냅샷
    title            TEXT     NOT NULL,
    body             TEXT     NOT NULL,
    data_json        BLOB     NULL,
    status           TEXT     NOT NULL, -- PENDING_RETRY | IN_PROGRESS | SUCCESS | INVALID | QUARANTINED | CANCELLED
    attempt_count    INTEGER  NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME NULL,
    last_error_class TEXT     NULL, -- RETRYABLE | UNCLASSIFIED
    claim_token      TEXT     NULL, -- IN_PROGRESS 동안만 값이 있으며 이 값이 일치하는 claim만 최종 상태를 기록할 수 있음(fencing)
    created_at       DATETIME NOT NULL,
    updated_at       DATETIME NOT NULL
);
CREATE UNIQUE INDEX uq_tb_notification_delivery_retry_event_installation
    ON tb_notification_delivery_retry (event_id, installation_id);
CREATE INDEX idx_tb_notification_delivery_retry_status_next_attempt
    ON tb_notification_delivery_retry (status, next_attempt_at);
CREATE INDEX idx_tb_notification_delivery_retry_status_updated_at
    ON tb_notification_delivery_retry (status, updated_at);

CREATE TABLE tb_channel_notification_preferences
(
    account_id           INTEGER  NOT NULL, -- cowork-user의 tb_accounts.id
    channel_id           INTEGER  NOT NULL, -- cowork-channel의 채널 ID
    notification_enabled BOOLEAN  NOT NULL DEFAULT TRUE,
    source_updated_at    DATETIME NOT NULL,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, channel_id)
);
CREATE INDEX idx_tb_channel_notification_preferences_channel_account
    ON tb_channel_notification_preferences (channel_id, account_id);

CREATE TABLE tb_user_profile_projections
(
    user_id           INTEGER  NOT NULL PRIMARY KEY, -- cowork-user의 tb_accounts.id
    display_name      TEXT     NOT NULL,
    deleted           BOOLEAN  NOT NULL DEFAULT FALSE,
    source_updated_at DATETIME NOT NULL,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tb_team_profile_projections
(
    team_id           INTEGER  NOT NULL PRIMARY KEY, -- cowork-team의 tb_teams.id
    team_name         TEXT     NOT NULL,
    deleted           BOOLEAN  NOT NULL DEFAULT FALSE,
    source_updated_at DATETIME NOT NULL,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tb_projection_checkpoints
(
    consumer_group            TEXT     NOT NULL,
    topic_name                TEXT     NOT NULL,
    partition_id              INTEGER  NOT NULL,
    topic_id                  TEXT     NOT NULL,
    next_offset               INTEGER  NOT NULL,
    invalid_record_offset     INTEGER  NULL,
    snapshot_completed_offset INTEGER  NULL,
    last_snapshot_id          TEXT     NULL,
    recovery_snapshot_id      TEXT     NULL,
    snapshot_id               TEXT     NULL,
    snapshot_source           TEXT     NULL,
    snapshot_occurred_at      DATETIME NULL,
    created_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_group, topic_name, partition_id)
);
CREATE INDEX idx_tb_projection_checkpoints_topic_partition
    ON tb_projection_checkpoints (topic_name, partition_id);

CREATE TABLE tb_projection_dead_letters
(
    consumer_group TEXT     NOT NULL,
    topic_name     TEXT     NOT NULL,
    partition_id   INTEGER  NOT NULL,
    topic_id       TEXT     NOT NULL,
    message_offset INTEGER  NOT NULL,
    event_key      BLOB     NULL,
    payload        BLOB     NOT NULL,
    reason         TEXT     NOT NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_group, topic_name, partition_id, message_offset)
);
CREATE INDEX idx_tb_projection_dead_letters_created_at ON tb_projection_dead_letters (created_at);
