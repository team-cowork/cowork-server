-- V1~V24 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `shedlock` (
  `name` varchar(64) NOT NULL,
  `lock_until` timestamp(3) NOT NULL,
  `locked_at` timestamp(3) NOT NULL,
  `locked_by` varchar(255) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_account_policy_projections` (
  `policy_key` varchar(100) NOT NULL,
  `record_type` varchar(16) NOT NULL,
  `role_id` bigint NOT NULL COMMENT 'cowork-preference.tb_account_role_definitions.id',
  `account_id` bigint DEFAULT NULL COMMENT 'cowork-user의 account id',
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  `permissions_json` text NOT NULL,
  `deleted` tinyint(1) NOT NULL,
  `source_occurred_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`policy_key`),
  KEY `idx_tb_account_policy_projections_account_id` (`account_id`,`role_id`),
  KEY `idx_tb_account_policy_projections_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(249) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `partition_id` int DEFAULT NULL,
  `event_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `payload` longtext NOT NULL,
  `is_barrier` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  `last_error` longtext,
  `failure_type` varchar(32) DEFAULT NULL,
  `claim_owner` varchar(128) DEFAULT NULL,
  `claim_until` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_kafka_outbox_status_next_attempt` (`status`,`next_attempt_at`,`claim_until`,`id`),
  KEY `idx_tb_kafka_outbox_topic_event_key_id` (`topic`,`event_key`,`id`),
  KEY `idx_tb_kafka_outbox_status_id` (`status`,`id`),
  CONSTRAINT `ck_tb_kafka_outbox_attempts` CHECK ((`attempts` >= 0)),
  CONSTRAINT `ck_tb_kafka_outbox_claim` CHECK ((((`claim_owner` is null) and (`claim_until` is null)) or ((`claim_owner` is not null) and (`claim_until` is not null)))),
  CONSTRAINT `ck_tb_kafka_outbox_partition` CHECK (((`partition_id` is null) or (`partition_id` >= 0))),
  CONSTRAINT `ck_tb_kafka_outbox_schedule` CHECK ((((`status` = _utf8mb4'PENDING') and (`next_attempt_at` is not null)) or ((`status` = _utf8mb4'QUARANTINED') and (`next_attempt_at` is null)))),
  CONSTRAINT `ck_tb_kafka_outbox_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'QUARANTINED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_outbox_fence` (
  `id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_tb_kafka_outbox_fence_singleton` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_barriers` (
  `consumer_group` varchar(190) NOT NULL,
  `topic_name` varchar(190) NOT NULL,
  `partition_id` int NOT NULL,
  `topic_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `target_offset` bigint NOT NULL,
  `captured_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_checkpoints` (
  `consumer_group` varchar(190) NOT NULL,
  `topic_name` varchar(190) NOT NULL,
  `partition_id` int NOT NULL,
  `topic_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `next_offset` bigint NOT NULL,
  `invalid_checkpoint_offset` bigint DEFAULT NULL,
  `invalid_record_offset` bigint DEFAULT NULL,
  `snapshot_completed_offset` bigint DEFAULT NULL,
  `last_snapshot_id` char(36) DEFAULT NULL,
  `recovery_snapshot_id` char(36) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`),
  CONSTRAINT `ck_tb_kafka_projection_checkpoints_invalid_record_offset` CHECK (((`invalid_record_offset` is null) or (`invalid_record_offset` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_quarantine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `consumer_group` varchar(190) NOT NULL,
  `topic_name` varchar(190) NOT NULL,
  `partition_id` int NOT NULL,
  `record_offset` bigint NOT NULL,
  `record_key` varchar(512) DEFAULT NULL,
  `payload` mediumtext,
  `reason` varchar(1000) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_kafka_projection_quarantine_record` (`consumer_group`,`topic_name`,`partition_id`,`record_offset`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_event_states` (
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id; 삭제 후에도 유지',
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `icon_url` varchar(512) DEFAULT NULL,
  `owner_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `github_installation_id` bigint DEFAULT NULL,
  `github_org_login` varchar(255) DEFAULT NULL,
  `actor_user_id` bigint NOT NULL COMMENT '마지막 상태 변경 actor의 cowork-user account id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`team_id`),
  KEY `idx_tb_team_event_states_deleted_team_id` (`deleted`,`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_github_installation_revisions` (
  `installation_id` bigint NOT NULL COMMENT '외부 cowork-github-app installation ID',
  `revision` bigint NOT NULL DEFAULT '0',
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`installation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_icon_uploads` (
  `object_key` varchar(255) NOT NULL,
  `upload_key` varchar(255) NOT NULL,
  `uploaded_by` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `content_type` varchar(100) NOT NULL,
  `confirmed` tinyint(1) NOT NULL DEFAULT '0',
  `team_id` bigint DEFAULT NULL COMMENT '아이콘을 연결한 tb_teams.id. 팀 삭제 후에도 재사용을 막기 위해 소유 기록을 유지',
  `retired` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`object_key`),
  UNIQUE KEY `uq_tb_team_icon_uploads_upload_key` (`upload_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_invites` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint NOT NULL,
  `invite_code` varchar(8) NOT NULL,
  `created_by` bigint NOT NULL,
  `duration` varchar(10) NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `invite_code` (`invite_code`),
  KEY `idx_tb_team_invites_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_member_event_states` (
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id; 삭제 후에도 유지',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `role` varchar(20) NOT NULL,
  `team_name` varchar(100) NOT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  `authorization_version` datetime(6) DEFAULT NULL COMMENT '가입·탈퇴·기본 역할 변경 버전',
  PRIMARY KEY (`team_id`,`user_id`),
  KEY `idx_tb_team_member_event_states_user_id_deleted` (`user_id`,`deleted`),
  CONSTRAINT `ck_tb_team_member_event_states_role` CHECK ((`role` in (_utf8mb4'OWNER',_utf8mb4'ADMIN',_utf8mb4'MEMBER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT 'cowork-authorization의 사용자 ID',
  `role` varchar(20) NOT NULL,
  `nickname` varchar(50) DEFAULT NULL COMMENT '팀 안에서 표시할 닉네임; NULL이면 계정 닉네임을 사용',
  `joined_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `created_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_team_members` (`team_id`,`user_id`),
  KEY `idx_tb_team_members_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_assignment_projections` (
  `projection_key` varchar(100) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user의 users.id',
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`),
  KEY `idx_tb_team_role_assignment_projections_team_account` (`team_id`,`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_command_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `actor_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `command_type` varchar(20) NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` varchar(20) NOT NULL,
  `result_role_json` longtext,
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `expected_projection_key` varchar(512) DEFAULT NULL,
  `expected_projection_occurred_at` datetime(6) DEFAULT NULL,
  `expected_projection_deleted` tinyint(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_team_role_command_operations_actor_key` (`actor_id`,`idempotency_key`),
  KEY `idx_tb_team_role_command_operations_team_created` (`team_id`,`created_at`),
  KEY `idx_tb_team_role_command_operations_processing` (`status`,`updated_at`),
  CONSTRAINT `ck_tb_team_role_command_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))),
  CONSTRAINT `ck_tb_team_role_command_operations_type` CHECK ((`command_type` in (_utf8mb4'CREATE',_utf8mb4'UPDATE',_utf8mb4'DELETE',_utf8mb4'ASSIGN',_utf8mb4'REVOKE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_member_tombstones` (
  `projection_key` varchar(80) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user의 users.id',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_projections` (
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `name` varchar(100) NOT NULL,
  `color_hex` varchar(7) NOT NULL,
  `priority` int NOT NULL,
  `mentionable` tinyint(1) NOT NULL,
  `permissions_json` text NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_created_at` datetime(6) NOT NULL,
  `source_occurred_at` datetime(6) NOT NULL,
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`role_id`),
  KEY `idx_tb_team_role_projections_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_setting_projections` (
  `team_id` bigint NOT NULL COMMENT 'cowork-preference 팀 설정 state의 teamId(cowork-team의 tb_teams.id)',
  `nickname_format_enforced` tinyint(1) NOT NULL DEFAULT '0',
  `nickname_format_example` text,
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_teams` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL DEFAULT '0' COMMENT 'cowork-authorization의 사용자 ID',
  `name` varchar(100) NOT NULL,
  `icon_url` varchar(512) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `created_by` bigint DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `last_modified_by` bigint DEFAULT NULL,
  `github_installation_id` bigint DEFAULT NULL COMMENT 'GitHub App installation ID',
  `github_org_login` varchar(255) DEFAULT NULL COMMENT 'GitHub App가 설치된 조직 로그인명',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_teams_github_installation_id` (`github_installation_id`),
  KEY `idx_tb_teams_visibility_id` (`id`),
  KEY `idx_tb_teams_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_kafka_outbox_fence (id) VALUES (1);
