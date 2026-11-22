-- V1~V24 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `tb_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Cowork 사용자 ID',
  `name` varchar(50) NOT NULL,
  `email` varchar(255) NOT NULL,
  `sex` varchar(10) DEFAULT NULL,
  `github` varchar(100) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `student_role` varchar(50) DEFAULT NULL,
  `student_number` varchar(30) DEFAULT NULL,
  `legacy_datagsm_student_id` bigint DEFAULT NULL COMMENT 'DataGSM의 student.id',
  `major` varchar(50) DEFAULT NULL,
  `specialty` varchar(255) DEFAULT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'offline',
  `custom_status` varchar(30) DEFAULT NULL,
  `status_message` varchar(100) DEFAULT NULL,
  `status_expires_at` datetime(6) DEFAULT NULL,
  `presence_updated_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `created_by` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `last_modified_by` bigint DEFAULT NULL,
  `datagsm_updated_at` datetime(6) DEFAULT NULL COMMENT '마지막으로 반영한 DataGSM student 이벤트 발생 시각',
  `profile_event_version` bigint unsigned NOT NULL DEFAULT '0' COMMENT 'user.profile.event의 단조 증가 source version',
  `profile_event_occurred_at` datetime(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000' COMMENT 'user.profile.event downstream LWW용 단조 증가 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_accounts_email` (`email`),
  UNIQUE KEY `uq_tb_accounts_github` (`github`),
  UNIQUE KEY `uq_tb_accounts_datagsm_student_id` (`legacy_datagsm_student_id`),
  KEY `idx_tb_accounts_student_number` (`student_number`),
  KEY `idx_tb_accounts_status_major_student_role_id` (`status`,`major`,`student_role`,`id`),
  KEY `idx_tb_accounts_major_student_role_id` (`major`,`student_role`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_action_quarantine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumer_group` varchar(191) NOT NULL,
  `topic_name` varchar(191) NOT NULL,
  `partition_id` int NOT NULL,
  `record_offset` bigint NOT NULL,
  `record_key` longblob,
  `payload` longblob NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `terminal_operation_id` char(36) DEFAULT NULL,
  `terminal_result_payload` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_kafka_action_quarantine_record` (`consumer_group`,`topic_name`,`partition_id`,`record_offset`),
  KEY `idx_tb_kafka_action_quarantine_created_at` (`created_at`),
  KEY `idx_tb_kafka_action_quarantine_terminal_operation_id` (`terminal_operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(255) NOT NULL,
  `event_key` varchar(255) NOT NULL,
  `partition_id` int DEFAULT NULL COMMENT 'NULL이면 key hash partition, marker는 명시 partition',
  `payload` json NOT NULL,
  `attempts` int unsigned NOT NULL DEFAULT '0',
  `last_error` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_kafka_outbox_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_barriers` (
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `partition_id` int NOT NULL,
  `marker_offset` bigint NOT NULL,
  `replay_generation` bigint NOT NULL DEFAULT '0',
  `replay_token` varchar(36) DEFAULT NULL,
  `snapshot_id` varchar(36) NOT NULL,
  `source_service` varchar(100) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `observed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_generations` (
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `replay_generation` bigint NOT NULL,
  `topic_id` varchar(36) DEFAULT NULL COMMENT 'Kafka 브로커가 보고한 토픽 UUID',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_offsets` (
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `partition_id` int NOT NULL,
  `next_offset` bigint NOT NULL,
  `invalid_record_offset` bigint DEFAULT NULL,
  `snapshot_completed_offset` bigint DEFAULT NULL,
  `last_snapshot_id` varchar(36) DEFAULT NULL,
  `recovery_snapshot_id` varchar(36) DEFAULT NULL,
  `replay_generation` bigint NOT NULL DEFAULT '0',
  `replay_token` varchar(36) DEFAULT NULL,
  `replay_owner` varchar(36) DEFAULT NULL,
  `replay_lease_expires_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_quarantine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumer_group` varchar(191) NOT NULL,
  `topic_name` varchar(191) NOT NULL,
  `partition_id` int NOT NULL,
  `record_offset` bigint NOT NULL,
  `record_key` longblob,
  `payload` longblob NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_kafka_projection_quarantine_record` (`consumer_group`,`topic_name`,`partition_id`,`record_offset`),
  KEY `idx_tb_kafka_projection_quarantine_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_member_projections` (
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `role` varchar(20) NOT NULL COMMENT 'OWNER | ADMIN | MEMBER',
  `team_name` varchar(100) NOT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `event_occurred_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`team_id`,`user_id`),
  KEY `idx_tb_team_member_projections_user_id_active` (`user_id`,`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_user_identity_command_inbox` (
  `operation_id` char(36) NOT NULL,
  `idempotency_key` varchar(128) NOT NULL,
  `user_id` bigint DEFAULT NULL COMMENT 'cowork-user.tb_accounts.id. 실패한 v3 명령은 NULL',
  `command_hash` char(64) NOT NULL,
  `hash_version` smallint NOT NULL DEFAULT '1' COMMENT '1 = Elixir(Jason) 직렬화 해시, 2 = System.Text.Json 정규 직렬화 해시',
  `result_status` varchar(20) NOT NULL COMMENT 'SUCCEEDED | FAILED',
  `result_payload` json NOT NULL,
  `result_hash` char(64) NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_user_identity_command_inbox_idempotency_key` (`idempotency_key`),
  KEY `idx_tb_user_identity_command_inbox_user_id_processed_at` (`user_id`,`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_user_presence_projections` (
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `status` varchar(30) NOT NULL COMMENT 'online | offline',
  `event_occurred_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_account_identities` (
  `account_id` bigint NOT NULL,
  `provider` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `provider_subject` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `last_login_command_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`provider`,`provider_subject`),
  UNIQUE KEY `uq_tb_account_identities_account_id_provider` (`account_id`,`provider`),
  CONSTRAINT `fk_tb_account_identities_account` FOREIGN KEY (`account_id`) REFERENCES `tb_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tb_gsm_student_info` (
  `account_id` bigint NOT NULL,
  `datagsm_student_id` bigint DEFAULT NULL,
  `sex` varchar(10) NOT NULL,
  `student_role` varchar(50) DEFAULT NULL,
  `student_number` varchar(30) DEFAULT NULL,
  `major` varchar(50) DEFAULT NULL,
  `specialty` varchar(255) DEFAULT NULL,
  `datagsm_updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uq_tb_gsm_student_info_datagsm_student_id` (`datagsm_student_id`),
  KEY `idx_tb_gsm_student_info_student_number` (`student_number`),
  KEY `idx_tb_gsm_student_info_major_student_role` (`major`,`student_role`),
  KEY `idx_tb_gsm_student_info_student_role` (`student_role`),
  CONSTRAINT `fk_tb_gsm_student_info_account` FOREIGN KEY (`account_id`) REFERENCES `tb_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_profiles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `profile_image_key` varchar(500) DEFAULT NULL COMMENT 'S3 object key (profiles/{userId}/{uuid}.ext)',
  `nickname` varchar(50) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `banner_color` varchar(20) DEFAULT NULL COMMENT '프로필 배경 색상 프리셋 키(RED, BLUE, GREEN, AMBER, PURPLE, NEUTRAL). NULL이면 기본 배경',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `created_by` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `last_modified_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_profiles_account_id` (`account_id`),
  KEY `idx_tb_profiles_nickname_account_id` (`nickname`,`account_id`),
  CONSTRAINT `fk_tb_profiles_account` FOREIGN KEY (`account_id`) REFERENCES `tb_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_profile_roles` (
  `profile_id` bigint NOT NULL,
  `role` varchar(50) NOT NULL,
  PRIMARY KEY (`profile_id`,`role`),
  KEY `idx_tb_profile_roles_role_profile_id` (`role`,`profile_id`),
  CONSTRAINT `fk_tb_profile_roles_profile` FOREIGN KEY (`profile_id`) REFERENCES `tb_profiles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
