CREATE TABLE tb_channel_notification_preferences
(
    account_id          BIGINT      NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    channel_id          BIGINT      NOT NULL COMMENT 'cowork-channel의 채널 ID',
    notification_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    source_updated_at    DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (account_id, channel_id),
    INDEX idx_tb_channel_notification_preferences_channel_account (channel_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tb_user_profile_projections
(
    user_id      BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    display_name VARCHAR(255) NOT NULL,
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    source_updated_at DATETIME(6) NOT NULL,
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tb_team_profile_projections
(
    team_id    BIGINT       NOT NULL COMMENT 'cowork-team의 tb_teams.id',
    team_name  VARCHAR(255) NOT NULL,
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    source_updated_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
