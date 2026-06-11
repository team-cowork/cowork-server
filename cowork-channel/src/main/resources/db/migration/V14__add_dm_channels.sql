-- DM 채널 지원: DM은 팀과 무관하므로 team_id를 nullable로 변경하고,
-- 두 사용자 쌍당 하나의 DM 채널만 존재하도록 dm_key 유니크 제약을 추가한다.
ALTER TABLE tb_channels MODIFY COLUMN team_id BIGINT NULL COMMENT '채널 소속 팀 ID (DM 채널은 NULL)';

ALTER TABLE tb_channels ADD COLUMN dm_key VARCHAR(50) NULL COMMENT 'DM 채널 식별키 (작은userId:큰userId, 일반 채널은 NULL)';

ALTER TABLE tb_channels ADD CONSTRAINT uq_tb_channels_dm_key UNIQUE (dm_key);
