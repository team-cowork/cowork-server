-- V1~V11 마이그레이션을 하나로 합친 기준 스키마입니다 (MySQL).

CREATE TABLE `tb_kafka_projection_barriers` (
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `partition_id` int NOT NULL,
  `topic_id` varchar(64) NOT NULL,
  `target_offset` bigint NOT NULL,
  `captured_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_kafka_projection_offsets` (
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `partition_id` int NOT NULL,
  `topic_id` varchar(64) NOT NULL,
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
  `consumer_group` varchar(255) NOT NULL,
  `topic_name` varchar(255) NOT NULL,
  `partition_id` int NOT NULL,
  `record_offset` bigint NOT NULL,
  `record_key` varchar(512) DEFAULT NULL,
  `payload` longtext,
  `reason` varchar(1000) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`consumer_group`,`topic_name`,`partition_id`,`record_offset`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_roadmap_role_policies` (
  `policy_key` varchar(128) NOT NULL,
  `team_id` bigint NOT NULL COMMENT 'cowork-team tb_teams.id, 0 for account policy',
  `kind` varchar(12) NOT NULL,
  `role_id` bigint DEFAULT NULL COMMENT 'cowork-preference tb_team_role_definitions.id or tb_account_role_definitions.id',
  `account_id` bigint DEFAULT NULL COMMENT 'cowork-user tb_accounts.id',
  `is_default` tinyint(1) NOT NULL,
  `permissions` json NOT NULL,
  `deleted` tinyint(1) NOT NULL,
  `source_version` datetime(6) NOT NULL,
  PRIMARY KEY (`policy_key`),
  KEY `idx_tb_roadmap_role_policies_team_id` (`team_id`,`kind`,`role_id`,`account_id`),
  KEY `idx_tb_roadmap_role_policies_account_id` (`team_id`,`kind`,`account_id`,`source_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_roadmaps` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(150) NOT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `category` varchar(50) NOT NULL COMMENT '종류/전공/포지션 (예: Flutter, Server, Web)',
  `scope` varchar(20) NOT NULL COMMENT 'GLOBAL | TEAM | PROJECT',
  `owner_team_id` bigint DEFAULT NULL COMMENT 'cowork-team의 tb_teams.id (커스텀 로드맵 소유 팀)',
  `owner_project_id` bigint DEFAULT NULL COMMENT 'cowork-project의 tb_projects.id (PROJECT 스코프)',
  `created_by` bigint DEFAULT NULL COMMENT '생성자 사용자 ID',
  `last_modified_by` bigint DEFAULT NULL COMMENT '최종 수정자 사용자 ID',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_tb_roadmaps_owner_project_id` (`owner_project_id`),
  KEY `idx_tb_roadmaps_scope_category` (`scope`,`category`),
  KEY `idx_tb_roadmaps_owner_team_id_category` (`owner_team_id`,`category`)
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

CREATE TABLE `tb_roadmap_assignments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `roadmap_id` bigint NOT NULL,
  `node_id` bigint DEFAULT NULL COMMENT 'NULL이면 로드맵 전체, 값이 있으면 특정 노드(서브트리) 할당',
  `scope` varchar(20) NOT NULL COMMENT 'TEAM | PROJECT (할당 맥락)',
  `team_id` bigint DEFAULT NULL COMMENT 'cowork-team의 tb_teams.id',
  `project_id` bigint DEFAULT NULL COMMENT 'cowork-project의 tb_projects.id',
  `assignee_user_id` bigint NOT NULL COMMENT '온보딩 대상 사용자 ID',
  `assigned_by` bigint NOT NULL COMMENT '과제 출제자 사용자 ID',
  `status` varchar(20) NOT NULL DEFAULT 'ASSIGNED' COMMENT 'ASSIGNED | IN_PROGRESS | DONE',
  `due_date` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `node_key` bigint GENERATED ALWAYS AS (coalesce(`node_id`,0)) STORED COMMENT 'node_id NULL을 0으로 치환한 유니크 판정용 값',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_roadmap_assignments_roadmap_node_key_assignee` (`roadmap_id`,`node_key`,`assignee_user_id`),
  KEY `idx_tb_roadmap_assignments_assignee_user_id` (`assignee_user_id`,`status`),
  KEY `idx_tb_roadmap_assignments_roadmap_id` (`roadmap_id`),
  CONSTRAINT `fk_tb_roadmap_assignments_roadmap` FOREIGN KEY (`roadmap_id`) REFERENCES `tb_roadmaps` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_roadmap_nodes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `roadmap_id` bigint NOT NULL,
  `parent_id` bigint DEFAULT NULL COMMENT '상위 노드 ID (NULL이면 루트). 서브트리 삭제는 애플리케이션에서 처리',
  `title` varchar(200) NOT NULL COMMENT '노드/문서 제목',
  `content` longtext COMMENT '번역된 본문 (마크다운)',
  `source_url` varchar(500) DEFAULT NULL COMMENT '원본 문서 URL',
  `source_title` varchar(300) DEFAULT NULL COMMENT '원본 문서 제목',
  `position` int NOT NULL DEFAULT '0' COMMENT '동일 부모 내 정렬 순서',
  `created_by` bigint DEFAULT NULL,
  `last_modified_by` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `parent_key` bigint GENERATED ALWAYS AS (coalesce(`parent_id`,0)) STORED COMMENT 'parent_id NULL을 0으로 치환한 유니크 판정용 값',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_roadmap_nodes_roadmap_parent_key_position` (`roadmap_id`,`parent_key`,`position`),
  KEY `idx_tb_roadmap_nodes_roadmap_id_parent_id` (`roadmap_id`,`parent_id`,`position`),
  CONSTRAINT `fk_tb_roadmap_nodes_roadmap` FOREIGN KEY (`roadmap_id`) REFERENCES `tb_roadmaps` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tb_roadmap_node_references` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `node_id` bigint NOT NULL,
  `title` varchar(200) NOT NULL COMMENT '관련 자료 제목',
  `url` varchar(500) NOT NULL COMMENT '관련 자료 링크',
  `position` int NOT NULL DEFAULT '0' COMMENT '노드 내 정렬 순서',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tb_roadmap_node_references_node_id_position` (`node_id`,`position`),
  CONSTRAINT `fk_tb_roadmap_node_references_node` FOREIGN KEY (`node_id`) REFERENCES `tb_roadmap_nodes` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
