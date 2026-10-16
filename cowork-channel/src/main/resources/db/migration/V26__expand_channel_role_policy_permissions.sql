ALTER TABLE tb_channel_role_policy_projections
    DROP CHECK ck_tb_channel_role_policy_projections_state;

ALTER TABLE tb_channel_role_policy_projections
    ADD COLUMN permissions_json JSON NULL
        COMMENT '채널 권한 덮어쓰기. true는 허용, false는 거부이며 누락된 키는 역할 기본 권한을 따른다'
        AFTER role_id;

UPDATE tb_channel_role_policy_projections
SET permissions_json = JSON_OBJECT(
    'message_read',
    IF(message_read, CAST('true' AS JSON), CAST('false' AS JSON))
)
WHERE deleted = FALSE;

ALTER TABLE tb_channel_role_policy_projections
    ADD CONSTRAINT ck_tb_channel_role_policy_projections_state CHECK (
        (deleted = FALSE AND permissions_json IS NOT NULL)
            OR (deleted = TRUE AND permissions_json IS NULL AND message_read IS NULL)
    );

ALTER TABLE tb_channel_role_policy_operations
    MODIFY COLUMN result_permissions_json TEXT NULL;
