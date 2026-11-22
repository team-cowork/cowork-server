-- V1~V34 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `tb_channel_projections` (
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `project_id` bigint DEFAULT NULL COMMENT 'cowork-channel의 tb_channels.project_id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`channel_id`),
  KEY `idx_tb_channel_projections_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_chat_github_issue_create_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `project_id` bigint NOT NULL COMMENT 'tb_projects.id',
  `requester_id` bigint NOT NULL COMMENT 'cowork-user id',
  `channel_id` bigint NOT NULL COMMENT 'cowork-chat 채널 id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `status` varchar(16) NOT NULL COMMENT 'PENDING, ACCEPTED, REJECTED',
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_chat_github_issue_create_operations_requester_key` (`requester_id`,`idempotency_key`),
  KEY `idx_tb_chat_github_issue_create_operations_project_id` (`project_id`),
  CONSTRAINT `ck_tb_chat_github_issue_create_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'ACCEPTED',_utf8mb4'REJECTED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_installation_ownership_fences` (
  `installation_id` bigint NOT NULL,
  `owner_team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `active` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`installation_id`),
  KEY `idx_tb_github_installation_ownership_fences_owner_team_id` (`owner_team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_issue_comment_projections` (
  `id` varchar(300) NOT NULL COMMENT '"owner/repo#commentId"',
  `owner` varchar(100) NOT NULL,
  `repo` varchar(100) NOT NULL,
  `comment_id` bigint NOT NULL,
  `parent_type` varchar(20) NOT NULL COMMENT 'ISSUE, PULL_REQUEST',
  `parent_number` int NOT NULL,
  `author` varchar(100) NOT NULL,
  `body` mediumtext NOT NULL,
  `html_url` varchar(512) NOT NULL,
  `github_created_at` varchar(40) NOT NULL,
  `github_updated_at` varchar(40) NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_issue_comment_projections_owner_repo_parent` (`owner`,`repo`,`parent_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_issue_projections` (
  `id` varchar(300) NOT NULL COMMENT '"owner/repo#number"',
  `owner` varchar(100) NOT NULL,
  `repo` varchar(100) NOT NULL,
  `number` int NOT NULL,
  `title` varchar(500) NOT NULL,
  `author` varchar(100) NOT NULL,
  `state` varchar(20) NOT NULL,
  `html_url` varchar(512) NOT NULL,
  `github_created_at` varchar(40) NOT NULL,
  `github_updated_at` varchar(40) NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_issue_projections_owner_repo_number` (`owner`,`repo`,`number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_issue_write_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `command_type` varchar(20) NOT NULL COMMENT 'REPLACE_LABELS, CREATE_COMMENT, UPDATE_COMMENT, DELETE_COMMENT',
  `repo_id` bigint NOT NULL COMMENT 'tb_project_github_repos.id',
  `owner` varchar(255) NOT NULL COMMENT 'GitHub organization/user login',
  `repo` varchar(255) NOT NULL COMMENT 'GitHub repository name',
  `issue_number` int DEFAULT NULL,
  `comment_id` bigint DEFAULT NULL,
  `parent_type` varchar(20) DEFAULT NULL COMMENT 'CREATE_COMMENT 전용: ISSUE 또는 PULL_REQUEST',
  `requested_by` bigint NOT NULL COMMENT 'cowork-user id',
  `status` varchar(16) NOT NULL COMMENT 'PENDING, SUCCEEDED, FAILED',
  `result_snapshot` text,
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_github_issue_write_operations_requester_key` (`requested_by`,`idempotency_key`),
  KEY `idx_tb_github_issue_write_operations_repo_id` (`repo_id`),
  CONSTRAINT `ck_tb_github_issue_write_operations_command_type` CHECK ((`command_type` in (_utf8mb4'REPLACE_LABELS',_utf8mb4'CREATE_COMMENT',_utf8mb4'UPDATE_COMMENT',_utf8mb4'DELETE_COMMENT'))),
  CONSTRAINT `ck_tb_github_issue_write_operations_parent_type` CHECK (((`parent_type` is null) or (`parent_type` in (_utf8mb4'ISSUE',_utf8mb4'PULL_REQUEST')))),
  CONSTRAINT `ck_tb_github_issue_write_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_label_projections` (
  `id` varchar(300) NOT NULL COMMENT '"owner/repo:name"',
  `owner` varchar(100) NOT NULL,
  `repo` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `color` varchar(10) NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_label_projections_owner_repo` (`owner`,`repo`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_project_item_projections` (
  `id` varchar(100) NOT NULL COMMENT 'GitHub ProjectV2Item node ID',
  `project_node_id` varchar(100) NOT NULL COMMENT 'GitHub ProjectV2 node ID',
  `owner` varchar(100) NOT NULL,
  `payload_json` mediumtext NOT NULL COMMENT 'github.project-item.state.v1 레코드',
  `archived` tinyint(1) NOT NULL DEFAULT '0',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_project_item_projections_project` (`project_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_project_item_write_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `command_type` varchar(20) NOT NULL COMMENT 'GitHub Project item write command type',
  `board_id` bigint NOT NULL COMMENT 'tb_project_github_boards.id',
  `project_id` bigint NOT NULL COMMENT 'tb_projects.id',
  `item_id` varchar(100) DEFAULT NULL COMMENT 'GitHub ProjectV2Item node ID. 아직 item이 없는 커맨드는 NULL',
  `requested_by` bigint NOT NULL COMMENT 'cowork-user.tb_accounts.id',
  `status` varchar(16) NOT NULL COMMENT 'PENDING, SUCCEEDED, FAILED',
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  KEY `idx_tb_github_project_item_write_operations_board_id` (`board_id`),
  CONSTRAINT `ck_tb_github_project_item_write_operations_command_type` CHECK ((`command_type` in (_utf8mb4'MOVE_ITEM',_utf8mb4'REORDER_ITEM',_utf8mb4'SET_FIELD',_utf8mb4'CLEAR_FIELD',_utf8mb4'CREATE_DRAFT',_utf8mb4'UPDATE_DRAFT',_utf8mb4'ADD_ITEM',_utf8mb4'REMOVE_ITEM',_utf8mb4'CONVERT_DRAFT',_utf8mb4'SET_ASSIGNEES'))),
  CONSTRAINT `ck_tb_github_project_item_write_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_project_projections` (
  `id` varchar(120) NOT NULL COMMENT '"owner/projectNumber"',
  `owner` varchar(100) NOT NULL,
  `project_number` int NOT NULL,
  `node_id` varchar(100) DEFAULT NULL COMMENT 'GitHub ProjectV2 node ID',
  `title` varchar(500) NOT NULL,
  `url` varchar(512) NOT NULL,
  `closed` tinyint(1) NOT NULL DEFAULT '0',
  `access_error` varchar(20) DEFAULT NULL COMMENT 'NOT_FOUND, FORBIDDEN',
  `fields_json` mediumtext NOT NULL COMMENT 'github.project.state.v1 fields',
  `views_json` mediumtext NOT NULL COMMENT 'github.project.state.v1 views',
  `item_ids_json` mediumtext NOT NULL COMMENT 'GitHub 위치 순서의 보관되지 않은 item node ID 목록',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_project_projections_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_pull_request_projections` (
  `id` varchar(300) NOT NULL COMMENT '"owner/repo#number"',
  `owner` varchar(100) NOT NULL,
  `repo` varchar(100) NOT NULL,
  `number` int NOT NULL,
  `title` varchar(500) NOT NULL,
  `body` mediumtext,
  `author` varchar(100) NOT NULL,
  `state` varchar(20) NOT NULL,
  `draft` tinyint(1) NOT NULL,
  `merged` tinyint(1) NOT NULL,
  `html_url` varchar(512) NOT NULL,
  `head_ref` varchar(300) NOT NULL,
  `base_ref` varchar(300) NOT NULL,
  `github_created_at` varchar(40) NOT NULL,
  `github_updated_at` varchar(40) NOT NULL,
  `files_source_occurred_at` datetime(6) NOT NULL,
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_pull_request_projections_owner_repo_number` (`owner`,`repo`,`number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_repo_preference_projections` (
  `repo_id` bigint NOT NULL COMMENT 'cowork-project의 tb_project_github_repos.id',
  `label_auto_apply` tinyint(1) NOT NULL DEFAULT '1',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`repo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_repo_projections` (
  `id` varchar(300) NOT NULL COMMENT '"owner/repo"',
  `installation_id` bigint NOT NULL,
  `owner` varchar(100) NOT NULL,
  `repo` varchar(100) NOT NULL,
  `full_name` varchar(201) NOT NULL,
  `is_private` tinyint(1) NOT NULL,
  `html_url` varchar(512) NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tb_github_repo_projections_owner` (`owner`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_repo_setting_operations` (
  `operation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `project_id` bigint NOT NULL COMMENT 'tb_projects.id',
  `repo_id` bigint NOT NULL COMMENT 'tb_project_github_repos.id',
  `requested_by` bigint NOT NULL COMMENT 'cowork-user id',
  `requested_auto_apply` tinyint(1) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED',
  `result_auto_apply` tinyint(1) DEFAULT NULL,
  `expected_state_occurred_at` datetime(6) DEFAULT NULL,
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`operation_id`),
  UNIQUE KEY `uq_tb_github_repo_setting_operations_requester_key` (`requested_by`,`idempotency_key`),
  KEY `idx_tb_github_repo_setting_operations_project_id` (`project_id`),
  KEY `idx_tb_github_repo_setting_operations_repo_id_status_created_at` (`repo_id`,`status`,`created_at`),
  CONSTRAINT `ck_tb_github_repo_setting_operations_result` CHECK ((((`status` = _utf8mb4'PENDING') and (`result_auto_apply` is null) and (`expected_state_occurred_at` is null) and (`error_code` is null) and (`error_message` is null)) or ((`status` in (_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED')) and (`result_auto_apply` is not null) and (`expected_state_occurred_at` is not null) and (`error_code` is null) and (`error_message` is null)) or ((`status` = _utf8mb4'FAILED') and (`result_auto_apply` is null) and (`expected_state_occurred_at` is null) and (`error_code` is not null) and (`error_message` is not null)))),
  CONSTRAINT `ck_tb_github_repo_setting_operations_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_sync_markers` (
  `id` varchar(300) NOT NULL COMMENT '"STREAM:owner/repo", org 단위 repo 목록은 "REPO:owner"',
  `completed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_sync_requests` (
  `id` varchar(300) NOT NULL COMMENT '"REPOSITORY:owner/repo" 또는 "ORG_REPOSITORIES:owner"',
  `requested_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
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

CREATE TABLE `tb_project_access_projections` (
  `access_key` varchar(255) NOT NULL,
  `project_id` bigint NOT NULL COMMENT 'cowork-project.tb_projects.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user.tb_accounts.id',
  `can_read` tinyint(1) NOT NULL,
  `can_write` tinyint(1) NOT NULL,
  `can_reorder` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` timestamp(6) NOT NULL,
  `can_manage` tinyint(1) NOT NULL DEFAULT '0',
  `can_read_private` tinyint(1) NOT NULL COMMENT '비공개 전환 후 읽기 가능 여부: cowork-preference.tb_project_access_states.can_read_private',
  `source_membership_version` datetime(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
  `source_authorization_version` datetime(6) DEFAULT NULL COMMENT '계산에 사용한 권한 버전: cowork-preference.tb_project_access_states.source_authorization_version',
  `can_board_read` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'GitHub Project 보드 보기 권한: cowork-preference.tb_project_access_states.can_board_read',
  `can_board_write` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'GitHub Project 보드 카드 이동 권한: cowork-preference.tb_project_access_states.can_board_write',
  PRIMARY KEY (`access_key`),
  UNIQUE KEY `uq_tb_project_access_projections_project_user` (`project_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_project_event_tombstones` (
  `project_id` bigint NOT NULL COMMENT 'deleted tb_projects.id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL COMMENT 'ACTIVE, ARCHIVED',
  `position` int NOT NULL,
  `state_occurred_at` datetime(6) NOT NULL COMMENT 'latest project.event DELETED version',
  PRIMARY KEY (`project_id`),
  KEY `idx_tb_project_event_tombstones_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_project_github_boards` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT 'tb_projects.id',
  `team_id` bigint NOT NULL COMMENT 'tb_projects.team_id 비정규화. 조직 소유 검사에 사용',
  `owner_login` varchar(100) NOT NULL COMMENT 'GitHub Project를 소유한 조직 login. 소문자로 저장',
  `project_number` int NOT NULL COMMENT 'GitHub Project 번호',
  `created_by` bigint NOT NULL COMMENT 'cowork-user.tb_accounts.id',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_project_github_boards_project_owner_number` (`project_id`,`owner_login`,`project_number`),
  KEY `idx_tb_project_github_boards_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_project_github_repo_event_tombstones` (
  `repo_id` bigint NOT NULL COMMENT 'deleted tb_project_github_repos.id',
  `project_id` bigint NOT NULL COMMENT 'deleted relation의 tb_projects.id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `github_repo_url` varchar(512) NOT NULL,
  `owner` varchar(255) NOT NULL,
  `repo` varchar(255) NOT NULL,
  `github_webhook_channel_id` bigint DEFAULT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `state_occurred_at` datetime(6) NOT NULL COMMENT 'latest project.github-repo.event DELETE version',
  PRIMARY KEY (`repo_id`),
  KEY `idx_tb_project_github_repo_event_tombstones_project_id` (`project_id`),
  KEY `idx_tb_project_github_repo_event_tombstones_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_project_github_repos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL COMMENT 'tb_projects.id',
  `team_id` bigint NOT NULL COMMENT 'tb_projects.team_id 비정규화(유니크 제약용)',
  `github_repo_url` varchar(512) NOT NULL,
  `github_webhook_channel_id` bigint DEFAULT NULL COMMENT 'cowork-channel의 tb_channels.id, GitHub 알림 수신 채널',
  `state_occurred_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'project.github-repo.event LWW version',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_project_github_repos_team_id_github_repo_url` (`team_id`,`github_repo_url`),
  KEY `idx_tb_project_github_repos_project_id` (`project_id`),
  KEY `idx_tb_project_github_repos_webhook_channel_id` (`github_webhook_channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_projects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint NOT NULL COMMENT '프로젝트 소속 팀 ID',
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, ARCHIVED',
  `position` int NOT NULL DEFAULT '0' COMMENT '팀 내 프로젝트 정렬 순서',
  `created_by` bigint NOT NULL DEFAULT '0' COMMENT 'cowork-user의 tb_users.id',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `state_occurred_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'project.event authoritative monotonic version',
  `is_private` tinyint(1) NOT NULL DEFAULT '1',
  `initial_read_membership_version` datetime(6) DEFAULT NULL COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.state_occurred_at',
  `initial_read_authorization_version` datetime(6) DEFAULT NULL COMMENT '최초 읽기 역할 승인 시 확인한 cowork-team.tb_team_member_event_states.authorization_version',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_projects_team_id_position` (`team_id`,`position`),
  KEY `idx_tb_projects_team_id_id` (`team_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_github_installation_event_states` (
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `installation_id` bigint DEFAULT NULL,
  `org_login` varchar(255) DEFAULT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`team_id`),
  KEY `idx_tb_team_github_installation_event_states_installation_id` (`installation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_github_installations` (
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `installation_id` bigint NOT NULL,
  `org_login` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`team_id`),
  UNIQUE KEY `uq_tb_team_github_installations_installation_id` (`installation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_memberships` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_users.id',
  `role` varchar(20) NOT NULL COMMENT 'OWNER, ADMIN, MEMBER',
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `source_occurred_at` datetime(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
  `authorization_version` datetime(6) DEFAULT NULL COMMENT '권한 변경 버전: cowork-team.tb_team_member_event_states.authorization_version',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_team_memberships_team_id_user_id` (`team_id`,`user_id`),
  KEY `idx_tb_team_memberships_user_id` (`user_id`),
  KEY `idx_tb_team_memberships_team_id_active` (`team_id`,`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_assignment_projections` (
  `projection_key` varchar(100) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team.tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user.tb_accounts.id',
  `role_id` bigint NOT NULL COMMENT 'cowork-preference.tb_team_role_definitions.id',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`),
  KEY `idx_tb_team_role_assignment_projections_team_account` (`team_id`,`account_id`),
  KEY `idx_tb_team_role_assignment_projections_team_role` (`team_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_member_tombstones` (
  `projection_key` varchar(80) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team.tb_teams.id',
  `account_id` bigint NOT NULL COMMENT 'cowork-user.tb_accounts.id',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`projection_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_team_role_projections` (
  `role_id` bigint NOT NULL COMMENT 'cowork-preference.tb_team_role_definitions.id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team.tb_teams.id',
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  `permissions_json` text,
  `priority` int NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`role_id`),
  KEY `idx_tb_team_role_projections_team_priority` (`team_id`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_user_profile_projections` (
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
  `github_id` varchar(255) DEFAULT NULL COMMENT 'cowork-user의 tb_accounts.github',
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  `source_occurred_at` datetime(6) NOT NULL,
  PRIMARY KEY (`user_id`),
  KEY `idx_tb_user_profile_projections_github_id` (`github_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_issue_projection_labels` (
  `issue_id` varchar(300) NOT NULL,
  `label` varchar(100) NOT NULL,
  KEY `idx_tb_github_issue_projection_labels_issue_id` (`issue_id`),
  CONSTRAINT `fk_tb_github_issue_projection_labels_issue` FOREIGN KEY (`issue_id`) REFERENCES `tb_github_issue_projections` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_pr_projection_files` (
  `pr_id` varchar(300) NOT NULL,
  `filename` varchar(1024) NOT NULL,
  `status` varchar(20) NOT NULL,
  `additions` int NOT NULL,
  `deletions` int NOT NULL,
  `patch` longtext,
  KEY `idx_tb_github_pr_projection_files_pr_id` (`pr_id`),
  CONSTRAINT `fk_tb_github_pr_projection_files_pr` FOREIGN KEY (`pr_id`) REFERENCES `tb_github_pull_request_projections` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_github_pr_projection_labels` (
  `pr_id` varchar(300) NOT NULL,
  `label` varchar(100) NOT NULL,
  KEY `idx_tb_github_pr_projection_labels_pr_id` (`pr_id`),
  CONSTRAINT `fk_tb_github_pr_projection_labels_pr` FOREIGN KEY (`pr_id`) REFERENCES `tb_github_pull_request_projections` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tb_kafka_outbox_fence (id) VALUES (1);
