CREATE TYPE resource_type AS ENUM ('ACCOUNT', 'TEAM', 'PROJECT', 'CHANNEL');

CREATE TABLE account_channel_notification
(
    account_id BIGINT      NOT NULL,
    channel_id BIGINT      NOT NULL,
    settings   JSONB       NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, channel_id)
);

CREATE INDEX idx_acn_account_id ON account_channel_notification (account_id);
CREATE INDEX idx_acn_channel_id ON account_channel_notification (channel_id);
CREATE INDEX idx_acn_settings ON account_channel_notification USING GIN (settings);

CREATE TABLE resource_setting
(
    resource_id   BIGINT        NOT NULL,
    resource_type resource_type NOT NULL,
    settings      JSONB         NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_id, resource_type)
);

CREATE INDEX idx_rs_settings ON resource_setting USING GIN (settings);
