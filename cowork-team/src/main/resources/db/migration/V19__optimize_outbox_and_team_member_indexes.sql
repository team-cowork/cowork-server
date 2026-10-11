ALTER TABLE tb_kafka_outbox
    ADD INDEX idx_tb_kafka_outbox_status_id (status, id),
    DROP INDEX idx_tb_kafka_outbox_id;

ALTER TABLE tb_team_members
    DROP INDEX idx_tb_team_members_team_id;
