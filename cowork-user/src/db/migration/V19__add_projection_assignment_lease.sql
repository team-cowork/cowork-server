ALTER TABLE tb_kafka_projection_offsets
    ADD COLUMN replay_lease_expires_at DATETIME(6) NULL AFTER replay_owner;
