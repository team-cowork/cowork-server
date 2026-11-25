CREATE TABLE `tb_channel_access_projections` (
  `access_key` varchar(50) NOT NULL COMMENT '{channel_id}:{user_id}',
  `channel_id` bigint NOT NULL COMMENT 'cowork-channel의 tb_channels.id',
  `user_id` bigint NOT NULL COMMENT 'cowork-user의 account id',
  `team_id` bigint NOT NULL COMMENT 'cowork-team의 tb_teams.id',
  `allowed_permissions` varchar(500) NOT NULL COMMENT '허용된 채널 권한 키를 쉼표로 이은 값. 없는 키는 거부다',
  `can_read_private` tinyint(1) NOT NULL COMMENT '채널이 비공개라면 접근할 수 있는지 여부',
  `source_occurred_at` datetime(6) NOT NULL,
  `source_membership_version` datetime(6) NOT NULL,
  `source_authorization_version` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`access_key`),
  KEY `idx_tb_channel_access_projections_channel_id` (`channel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='cowork-preference의 preference.channel-access.state.v1 projection';
