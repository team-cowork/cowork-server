-- V1~V36 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `shedlock` (
  `name` varchar(64) NOT NULL,
  `lock_until` timestamp(3) NOT NULL,
  `locked_at` timestamp(3) NOT NULL,
  `locked_by` varchar(255) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_channel_event_states` (
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id; 삭제 후에도 유지',
  `team_id` bigint DEFAULT NULL COMMENT 'cowork-team의 tb_teams.id',
  `project_id` bigint DEFAULT NULL COMMENT 'cowork-project의 tb_projects.id',
  `name` varchar(100) NOT NULL,
  `type` varchar(20) NOT NULL,
  `view_type` varchar(30) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `is_private` tinyint(1) NOT NULL,
  `position` int NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  `initial_role_policy_seed_json` json DEFAULT NULL COMMENT '생성 시 요청한 역할 정책; 이후 상태 변경과 snapshot에서도 보존',
  `archived` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'channel.event.v2로 발행한 보관 상태',
  PRIMARY KEY (`channel_id`),
  KEY `idx_tb_channel_event_states_deleted_channel_id` (`deleted`,`channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_channel_role_policy_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `actor_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `command_type` varchar(20) NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` varchar(20) NOT NULL,
  `result_permissions_json` text,
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `expected_projection_key` varchar(120) DEFAULT NULL,
  `expected_projection_occurred_at` datetime(6) DEFAULT NULL,
  `expected_projection_deleted` tinyint(1) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_channel_role_policy_operations_actor_key` (`actor_id`,`idempotency_key`),
  KEY `idx_tb_channel_role_policy_operations_channel_created` (`channel_id`,`created_at`),
  KEY `idx_tb_channel_role_policy_operations_processing` (`status`,`updated_at`),
  CONSTRAINT `ck_tb_channel_role_policy_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))),
  CONSTRAINT `ck_tb_channel_role_policy_operations_type` CHECK ((`command_type` in (_utf8mb4'UPSERT',_utf8mb4'DELETE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_channel_role_policy_projections` (
  `policy_key` varchar(120) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `permissions_json` json DEFAULT NULL COMMENT '채널 권한 덮어쓰기. true는 허용, false는 거부이며 누락된 키는 역할 기본 권한을 따른다',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`policy_key`),
  KEY `idx_tb_channel_role_policy_projections_channel_role` (`channel_id`,`role_id`),
  KEY `idx_tb_channel_role_policy_projections_team_channel` (`team_id`,`channel_id`),
  CONSTRAINT `ck_tb_channel_role_policy_projections_state` CHECK ((((`deleted` = false) and (`permissions_json` is not null)) or ((`deleted` = true) and (`permissions_json` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_channels` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint DEFAULT NULL COMMENT '채널 소속 팀 ID (DM 채널은 NULL)',
  `project_id` bigint DEFAULT NULL COMMENT 'cowork-project의 tb_projects.id',
  `type` varchar(20) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT, VOICE',
  `view_type` varchar(30) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT, VOICE, WEBHOOK, ACCOUNT_SHARE, FILE_SHARE, MEETING_NOTE',
  `description` varchar(500) DEFAULT NULL,
  `is_private` tinyint(1) NOT NULL DEFAULT '0',
  `position` int NOT NULL DEFAULT '0' COMMENT '팀 내 채널 정렬 순서',
  `name` varchar(100) NOT NULL,
  `notice` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `created_by` bigint NOT NULL DEFAULT '0' COMMENT 'cowork-user의 tb_users.id',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `last_modified_by` bigint DEFAULT NULL,
  `dm_key` varchar(50) DEFAULT NULL COMMENT 'DM 채널 식별키 (작은userId:큰userId, 일반 채널은 NULL)',
  `archived_at` datetime(6) DEFAULT NULL COMMENT '보관 시각. NULL이면 활성 채널',
  `archived_by` bigint DEFAULT NULL COMMENT '보관한 사용자. cowork-user의 account id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_channels_dm_key` (`dm_key`),
  UNIQUE KEY `uq_tb_channels_team_id_position` (`team_id`,`position`),
  KEY `idx_tb_channels_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_dm_participant_event_states` (
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id; 삭제 후에도 유지',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `team_id` bigint DEFAULT NULL COMMENT 'cowork-team의 tb_teams.id',
  `role` varchar(20) NOT NULL,
  `channel_type` varchar(20) NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`channel_id`,`user_id`),
  KEY `idx_tb_dm_participant_event_states_user_id_deleted` (`user_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DM 참여 상태와 삭제 이력';

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
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`)
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

CREATE TABLE `tb_meeting_note_attachment_states` (
  `note_id` bigint NOT NULL COMMENT '회의록 삭제 후에도 발행 상태를 보존합니다.',
  `channel_id` bigint NOT NULL,
  `author_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `attachments_json` json NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`note_id`),
  KEY `idx_tb_meeting_note_attachment_states_channel_id` (`channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_project_access_projections` (
  `access_key` varchar(50) NOT NULL COMMENT '{project_id}:{user_id}',
  `project_id` bigint NOT NULL COMMENT 'cowork-project의 tb_projects.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `can_read` tinyint(1) NOT NULL,
  `can_channel_archive` tinyint(1) NOT NULL,
  `source_occurred_at` datetime(6) NOT NULL,
  `source_membership_version` datetime(6) NOT NULL,
  `source_authorization_version` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`access_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='cowork-preference의 preference.project-access.state.v1 projection';

CREATE TABLE `tb_project_projections` (
  `project_id` bigint NOT NULL COMMENT 'cowork-project의 tb_projects.id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`project_id`),
  KEY `idx_tb_project_projections_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_memberships` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `role` varchar(20) NOT NULL COMMENT 'OWNER, ADMIN, MEMBER',
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `source_occurred_at` datetime(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
  `authorization_version` datetime(6) DEFAULT NULL COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version; 구형 이벤트는 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_team_memberships_team_id_user_id` (`team_id`,`user_id`),
  KEY `idx_tb_team_memberships_user_id` (`user_id`),
  KEY `idx_tb_team_memberships_team_id_active` (`team_id`,`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_assignment_projections` (
  `projection_key` varchar(100) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`),
  KEY `idx_tb_team_role_assignment_projections_team_account` (`team_id`,`account_id`),
  KEY `idx_tb_team_role_assignment_projections_team_role` (`team_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_member_tombstones` (
  `projection_key` varchar(80) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_projections` (
  `role_id` bigint NOT NULL COMMENT 'cowork-preference의 team role id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `priority` int NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  `permissions_json` text,
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`role_id`),
  KEY `idx_tb_team_role_projections_team_priority` (`team_id`,`priority`),
  KEY `idx_tb_team_role_projections_team_default` (`team_id`,`is_default`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_thread_states` (
  `thread_id` bigint NOT NULL COMMENT '스레드 삭제 후에도 발행 상태를 보존합니다.',
  `channel_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `parent_message_id` varchar(24) NOT NULL COMMENT 'cowork-chat.messages의 MongoDB ObjectId',
  `created_by` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `status` varchar(20) NOT NULL,
  `archived` tinyint(1) NOT NULL DEFAULT '0',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `state_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`thread_id`),
  KEY `idx_tb_thread_states_channel_id` (`channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_channel_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `provider` varchar(50) NOT NULL COMMENT 'AccountProvider enum 값',
  `provider_label` varchar(100) DEFAULT NULL COMMENT 'provider=CUSTOM일 때 사용자 정의 서비스 이름',
  `account_identifier` varchar(255) DEFAULT NULL COMMENT 'username 또는 email',
  `credential` text COMMENT 'AES-256-GCM 암호화된 password 또는 token',
  `connected_via_oauth` tinyint(1) NOT NULL DEFAULT '0',
  `created_by` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_channel_accounts_channel_provider_identifier` (`channel_id`,`provider`,`account_identifier`),
  KEY `idx_tb_channel_accounts_channel_id_created_at` (`channel_id`,`created_at`),
  CONSTRAINT `fk_tb_channel_accounts_channel` FOREIGN KEY (`channel_id`) REFERENCES `tb_channels` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_dm_participants` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `joined_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_dm_participants_channel_id_user_id` (`channel_id`,`user_id`),
  KEY `idx_tb_dm_participants_user_id` (`user_id`),
  CONSTRAINT `fk_tb_dm_participants_channel` FOREIGN KEY (`channel_id`) REFERENCES `tb_channels` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DM 참여자';

CREATE TABLE `tb_meeting_note_templates` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_id` bigint NOT NULL COMMENT 'tb_channels.id',
  `name` varchar(100) NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT '0',
  `created_by` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_meeting_note_templates_channel_id_is_active` (`channel_id`,`is_active`),
  CONSTRAINT `fk_tb_meeting_note_templates_channel` FOREIGN KEY (`channel_id`) REFERENCES `tb_channels` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_threads` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `parent_message_id` varchar(24) NOT NULL COMMENT 'cowork-chat.messages의 MongoDB ObjectId',
  `is_archived` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING: 시작 메시지 검증 대기, ACTIVE: 확정',
  `created_by` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_threads_parent_message_id` (`parent_message_id`),
  KEY `idx_tb_threads_channel_id` (`channel_id`),
  CONSTRAINT `fk_tb_threads_channel` FOREIGN KEY (`channel_id`) REFERENCES `tb_channels` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_account_credential_copies` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL COMMENT 'tb_channel_accounts.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `copied_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_account_credential_copies_account_id` (`account_id`),
  CONSTRAINT `fk_tb_account_credential_copies_account` FOREIGN KEY (`account_id`) REFERENCES `tb_channel_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_meeting_note_template_sections` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `template_id` bigint NOT NULL COMMENT 'tb_meeting_note_templates.id',
  `title` varchar(100) NOT NULL,
  `type` varchar(20) NOT NULL COMMENT 'TEXT, MARKDOWN, DATE, DATETIME, USER_LIST',
  `placeholder` varchar(255) DEFAULT NULL,
  `is_required` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_meeting_note_template_sections_template_id` (`template_id`),
  CONSTRAINT `fk_tb_meeting_note_template_sections_template` FOREIGN KEY (`template_id`) REFERENCES `tb_meeting_note_templates` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_meeting_notes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `channel_id` bigint NOT NULL COMMENT 'tb_channels.id',
  `template_id` bigint DEFAULT NULL COMMENT 'tb_meeting_note_templates.id. 템플릿 없이 작성하면 NULL',
  `title` varchar(200) NOT NULL,
  `content` longtext NOT NULL COMMENT '섹션별 작성 내용 JSON 객체 문자열 (키 순서 보존)',
  `created_by` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_meeting_notes_channel_id` (`channel_id`),
  KEY `idx_tb_meeting_notes_template_id` (`template_id`),
  CONSTRAINT `fk_tb_meeting_notes_channel` FOREIGN KEY (`channel_id`) REFERENCES `tb_channels` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_tb_meeting_notes_template` FOREIGN KEY (`template_id`) REFERENCES `tb_meeting_note_templates` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_meeting_note_attachments` (
  `note_id` bigint NOT NULL,
  `position` int NOT NULL,
  `object_key` varchar(200) NOT NULL,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`note_id`,`position`),
  CONSTRAINT `fk_tb_meeting_note_attachments_note` FOREIGN KEY (`note_id`) REFERENCES `tb_meeting_notes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_kafka_outbox_fence (id) VALUES (1);
