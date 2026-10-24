ALTER TABLE project_role_definition ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
CREATE TABLE tb_project_access_states (
    project_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    can_read BOOLEAN NOT NULL,
    can_write BOOLEAN NOT NULL,
    can_reorder BOOLEAN NOT NULL DEFAULT FALSE,
    state_occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, account_id)
);
COMMENT ON COLUMN tb_project_access_states.project_id IS 'cowork-project.tb_projects.id';
COMMENT ON COLUMN tb_project_access_states.account_id IS 'cowork-user.tb_accounts.id';

ALTER TABLE tb_project_lifecycle_projections ADD COLUMN created_by BIGINT NULL;
COMMENT ON COLUMN tb_project_lifecycle_projections.created_by IS 'cowork-user.tb_accounts.id';

ALTER TABLE tb_channel_lifecycle_projections ADD COLUMN channel_type VARCHAR(30) NULL;

ALTER TABLE tb_channel_member_projections RENAME TO tb_dm_participant_projections;
ALTER TABLE tb_dm_participant_projections
    RENAME CONSTRAINT pk_tb_channel_member_projections TO pk_tb_dm_participant_projections;
ALTER TABLE tb_dm_participant_projections
    RENAME CONSTRAINT ck_tb_channel_member_projections_channel_id TO ck_tb_dm_participant_projections_channel_id;
ALTER TABLE tb_dm_participant_projections
    RENAME CONSTRAINT ck_tb_channel_member_projections_account_id TO ck_tb_dm_participant_projections_account_id;

COMMENT ON TABLE tb_dm_participant_projections IS
    'channel.dm-participant.state.v1 DM 참여 상태. 삭제 상태도 source version과 함께 보존한다';
COMMENT ON COLUMN tb_dm_participant_projections.account_id IS 'cowork-user.tb_accounts.id';
COMMENT ON COLUMN tb_dm_participant_projections.source_occurred_at IS
    'cowork-channel DM 참여 상태의 source mutation version';
