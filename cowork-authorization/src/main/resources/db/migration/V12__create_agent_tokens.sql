CREATE TABLE `tb_agent_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `name` varchar(100) NOT NULL,
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `scopes` varchar(255) NOT NULL COMMENT '공백으로 구분한 scope 목록',
  `expires_at` datetime(6) NOT NULL,
  `last_used_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_agent_tokens_token_hash` (`token_hash`),
  KEY `idx_tb_agent_tokens_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
