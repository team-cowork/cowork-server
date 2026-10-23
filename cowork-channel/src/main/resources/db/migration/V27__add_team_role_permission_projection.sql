ALTER TABLE tb_team_role_projections ADD COLUMN permissions_json TEXT NULL;

RENAME TABLE tb_channel_members TO tb_dm_participants,
    tb_channel_member_event_states TO tb_dm_participant_event_states;

ALTER TABLE tb_dm_participants
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    RENAME INDEX uq_tb_channel_members_channel_id_user_id TO uq_tb_dm_participants_channel_id_user_id,
    RENAME INDEX idx_tb_channel_members_user_id TO idx_tb_dm_participants_user_id,
    DROP FOREIGN KEY fk_tb_channel_members_channel,
    ADD CONSTRAINT fk_tb_dm_participants_channel FOREIGN KEY (channel_id) REFERENCES tb_channels (id) ON DELETE CASCADE,
    COMMENT = 'DM 참여자';

ALTER TABLE tb_dm_participant_event_states
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT 'cowork-user의 tb_accounts.id',
    RENAME INDEX idx_tb_channel_member_event_states_user_id_deleted TO idx_tb_dm_participant_event_states_user_id_deleted,
    COMMENT = 'DM 참여 상태와 삭제 이력';
