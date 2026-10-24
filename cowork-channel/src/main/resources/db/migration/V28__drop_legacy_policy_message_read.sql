ALTER TABLE tb_channel_role_policy_projections
    DROP CHECK ck_tb_channel_role_policy_projections_state,
    DROP COLUMN message_read,
    ADD CONSTRAINT ck_tb_channel_role_policy_projections_state CHECK (
        (deleted = FALSE AND permissions_json IS NOT NULL)
            OR (deleted = TRUE AND permissions_json IS NULL)
    );
