-- V1 projection은 notification_level과 muted_until을 버렸다. 데이터와 checkpoint를 함께 비워 compact topic에서 다시 구축한다.
DROP TABLE tb_channel_notification_preferences;

CREATE TABLE tb_channel_notification_preferences
(
    account_id         INTEGER  NOT NULL, -- cowork-user의 tb_accounts.id
    channel_id         INTEGER  NOT NULL, -- cowork-channel의 채널 ID
    notification_level TEXT     NOT NULL, -- ALL | MENTIONS | NONE
    muted_until        DATETIME NULL,
    source_updated_at  DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, channel_id)
);
CREATE INDEX idx_tb_channel_notification_preferences_channel_account
    ON tb_channel_notification_preferences (channel_id, account_id);

DELETE FROM tb_projection_checkpoints
WHERE topic_name = 'preference.channel-notification.changed';
