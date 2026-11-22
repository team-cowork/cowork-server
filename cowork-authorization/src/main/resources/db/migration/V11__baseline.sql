-- V1~V11 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `tb_refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `email` varchar(255) NOT NULL DEFAULT '',
  `gsm_role` varchar(30) NOT NULL DEFAULT '',
  `platform_role` varchar(20) NOT NULL DEFAULT 'MEMBER',
  `device_info` varchar(255) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_refresh_tokens_hash` (`token_hash`),
  KEY `idx_tb_refresh_tokens_expires_at` (`expires_at`),
  KEY `idx_tb_refresh_tokens_user_id_expires_at` (`user_id`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_user_identity_operations` (
  `operation_id` char(36) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `user_id` bigint DEFAULT NULL COMMENT '기존 v2 명령의 cowork-user.tb_accounts.id',
  `request_hash` char(64) NOT NULL,
  `status` varchar(20) NOT NULL COMMENT 'PENDING | SUCCEEDED | FAILED',
  `result_user_id` bigint DEFAULT NULL COMMENT 'cowork-user가 commit한 tb_accounts.id',
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `result_hash` char(64) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `completed_at` datetime(6) DEFAULT NULL,
  `result_email` varchar(255) DEFAULT NULL,
  `result_gsm_role` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_user_identity_operations_idempotency_key` (`idempotency_key`),
  KEY `idx_tb_user_identity_operations_status_updated_at` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_user_presence_states` (
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `status` varchar(30) NOT NULL COMMENT 'online | offline',
  `active_session_count` int unsigned NOT NULL DEFAULT '0',
  `occurred_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_webhook_inbox` (
  `event_id` varbinary(255) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `payload_hash` binary(32) NOT NULL,
  `message_count` int unsigned NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `accepted_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_tb_webhook_inbox_expires_at` (`expires_at`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(255) NOT NULL,
  `partition_id` int DEFAULT NULL,
  `event_key` varchar(255) NOT NULL,
  `payload` json NOT NULL,
  `attempts` int unsigned NOT NULL DEFAULT '0',
  `last_error` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `source_event_id` varbinary(255) DEFAULT NULL,
  `source_event_index` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_kafka_outbox_source_event` (`source_event_id`,`source_event_index`),
  KEY `idx_tb_kafka_outbox_created_at` (`created_at`),
  CONSTRAINT `fk_tb_kafka_outbox_webhook_inbox` FOREIGN KEY (`source_event_id`) REFERENCES `tb_webhook_inbox` (`event_id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_tb_kafka_outbox_source_event` CHECK ((((`source_event_id` is null) and (`source_event_index` is null)) or ((`source_event_id` is not null) and (`source_event_index` is not null) and (`source_event_index` >= 0))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
