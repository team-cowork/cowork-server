UPDATE tb_accounts
SET custom_status = status
WHERE status NOT IN ('online', 'offline');

UPDATE tb_accounts AS account
JOIN tb_user_presence_projections AS presence ON presence.user_id = account.id
SET account.status = presence.status,
    account.presence_updated_at = presence.event_occurred_at;

UPDATE tb_accounts AS account
LEFT JOIN tb_user_presence_projections AS presence ON presence.user_id = account.id
SET account.status = 'offline',
    account.presence_updated_at = '1970-01-01 00:00:00.000000'
WHERE presence.user_id IS NULL;
