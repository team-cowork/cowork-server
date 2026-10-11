ALTER TABLE tb_kafka_outbox
    DROP INDEX idx_tb_kafka_outbox_id,
    ADD INDEX idx_tb_kafka_outbox_status_id (status, id);

ALTER TABLE tb_channels
    DROP INDEX idx_tb_channels_team_id_type_id,
    DROP INDEX idx_tb_channels_team_id_name,
    DROP INDEX idx_tb_channels_created_by;

ALTER TABLE tb_meeting_note_templates
    DROP INDEX idx_tb_meeting_note_templates_channel_id;
