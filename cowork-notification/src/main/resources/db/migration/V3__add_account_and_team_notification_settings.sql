CREATE TABLE tb_account_notification_settings
(
    account_id         INTEGER  NOT NULL PRIMARY KEY, -- cowork-user의 tb_accounts.id
    notification_level TEXT     NULL,                 -- ALL | MENTIONS | NONE
    status             TEXT     NULL,                 -- ONLINE | DO_NOT_DISTURB
    status_expires_at  DATETIME NULL,
    timezone           TEXT     NULL,                 -- IANA time zone
    dnd_enabled        BOOLEAN  NOT NULL DEFAULT FALSE,
    dnd_start_minute   INTEGER  NULL,                 -- timezone 기준 자정부터 지난 분
    dnd_end_minute     INTEGER  NULL,
    source_updated_at  DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tb_team_notification_settings
(
    team_id                    INTEGER  NOT NULL PRIMARY KEY, -- cowork-team의 tb_teams.id
    default_notification_level TEXT     NULL,                 -- ALL | MENTIONS | NONE
    source_updated_at          DATETIME NOT NULL,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
