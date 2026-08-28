ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic;

ALTER TABLE tb_preference_event_outbox
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic
        CHECK (topic IN (
            'preference.channel-notification.changed',
            'preference.team-role.changed',
            'preference.status.changed',
            'preference.team.setting.changed'
        ));
