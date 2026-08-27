CREATE TABLE tb_preference_event_outbox
(
    id           BIGSERIAL    NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    record_key   TEXT         NOT NULL,
    payload      JSONB        NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT pk_tb_preference_event_outbox PRIMARY KEY (id),
    CONSTRAINT ck_tb_preference_event_outbox_topic
        CHECK (topic IN ('preference.channel-notification.changed', 'preference.team-role.changed')),
    CONSTRAINT ck_tb_preference_event_outbox_record_key
        CHECK (length(record_key) > 0)
);

CREATE INDEX idx_tb_preference_event_outbox_unpublished
    ON tb_preference_event_outbox (id)
    WHERE published_at IS NULL;

CREATE INDEX idx_tb_preference_event_outbox_published_at
    ON tb_preference_event_outbox (published_at)
    WHERE published_at IS NOT NULL;
