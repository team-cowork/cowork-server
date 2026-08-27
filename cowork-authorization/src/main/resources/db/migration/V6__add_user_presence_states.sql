CREATE TABLE tb_user_presence_states
(
    user_id              BIGINT       NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    status               VARCHAR(30)  NOT NULL COMMENT 'online | offline',
    active_session_count INT UNSIGNED NOT NULL DEFAULT 0,
    occurred_at           DATETIME(6)  NOT NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO tb_user_presence_states
    (user_id, status, active_session_count, occurred_at)
SELECT token.user_id,
       CASE
           WHEN SUM(token.expires_at > UTC_TIMESTAMP(6)) > 0 THEN 'online'
           ELSE 'offline'
       END,
       SUM(token.expires_at > UTC_TIMESTAMP(6)),
       CASE
           WHEN SUM(token.expires_at > UTC_TIMESTAMP(6)) > 0
               THEN MIN(CASE WHEN token.expires_at > UTC_TIMESTAMP(6) THEN token.created_at END)
           ELSE MAX(token.expires_at)
       END
FROM tb_refresh_tokens AS token
GROUP BY token.user_id
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    active_session_count = VALUES(active_session_count),
    occurred_at = VALUES(occurred_at);
