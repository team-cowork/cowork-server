ALTER TABLE tb_preference_event_outbox
    DROP CONSTRAINT ck_tb_preference_event_outbox_topic,
    ADD CONSTRAINT ck_tb_preference_event_outbox_topic_not_blank
        CHECK (topic ~ '[^[:space:]]');
