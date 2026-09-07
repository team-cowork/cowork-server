-- invalid-record latch를 서로 다른 두 full snapshot으로 해제하기 위한 recovery 상태.
-- 다른 projection 소비자(cowork-project V17, cowork-chat, cowork-voice, cowork-user)와 동일한 계약이다.
ALTER TABLE tb_kafka_projection_checkpoints
    ADD COLUMN last_snapshot_id     CHAR(36) NULL AFTER snapshot_completed_offset,
    ADD COLUMN recovery_snapshot_id CHAR(36) NULL AFTER last_snapshot_id;
