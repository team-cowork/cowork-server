ALTER TABLE tb_accounts
    ADD COLUMN profile_event_version BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'user.profile.event의 단조 증가 source version',
    ADD COLUMN profile_event_occurred_at DATETIME(6) NULL
        COMMENT 'user.profile.event downstream LWW용 단조 증가 시각';

UPDATE tb_accounts AS account
    LEFT JOIN tb_profiles AS profile ON profile.account_id = account.id
SET account.profile_event_version = 1,
    account.profile_event_occurred_at = GREATEST(
        COALESCE(account.datagsm_updated_at, '1970-01-01 00:00:00.000000'),
        COALESCE(account.updated_at, '1970-01-01 00:00:00.000000'),
        COALESCE(profile.updated_at, '1970-01-01 00:00:00.000000')
    );

ALTER TABLE tb_accounts
    MODIFY COLUMN profile_event_occurred_at DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000'
        COMMENT 'user.profile.event downstream LWW용 단조 증가 시각';
