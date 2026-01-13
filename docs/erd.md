# 테이블 구조

모든 테이블 이름은 `tb_` 접두사를 쓴다. 대부분의 테이블은 생성·수정 시각을 `created_at`, `updated_at` 컬럼으로 둔다.

## 인증·사용자

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `tb_users` | `datagsm_id`, `email`, `grade`, `class_num`, `major`, `role` | DataGSM ID와 이메일은 유일 |
| `tb_refresh_tokens` | `user_id`, `token_hash`, `expires_at`, `device_info` | 토큰 원문은 저장하지 않음 |

## 팀·프로젝트

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `tb_teams` | `name`, `visibility`, `owner_id`, `icon_key` | |
| `tb_team_members` | `team_id`, `user_id`, `role` | (팀, 사용자) 유일 |
| `tb_projects` | `team_id`, `name`, `position`, `status` | 팀 안에서 `position` 순서 |
| `tb_project_members` | `project_id`, `user_id`, `role` | (프로젝트, 사용자) 유일 |

## 채널·채팅

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `tb_channels` | `team_id`, `project_id`, `view_type`, `is_private`, `position` | 프로젝트 삭제 시 `project_id`만 비움 |
| `tb_channel_members` | `channel_id`, `user_id` | 비공개 채널 참여자 |
| `tb_messages` | `channel_id`, `thread_id`, `sender_id`, `webhook_id`, `content` | (채널, ID) 인덱스로 커서 조회 |
| `tb_message_attachments` | `message_id`, `channel_id`, `object_key` | 객체 키는 유일 |
| `tb_webhooks` | `channel_id`, `token` | |
| `tb_threads` | `channel_id`, `parent_message_id`, `is_archived` | 부모 메시지당 하나 |

## 설정·알림·음성

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `tb_resource_settings` | `resource_type`, `resource_id`, `settings`(JSON) | (유형, ID) 유일 |
| `tb_channel_notification_settings` | `user_id`, `channel_id`, `enabled` | |
| `tb_device_tokens` | `user_id`, `token`, `platform` | 토큰은 유일 |
| `tb_voice_sessions` | `channel_id`, `room_name`, `status` | |
| `tb_voice_participants` | `session_id`, `user_id`, `joined_at`, `left_at` | |
