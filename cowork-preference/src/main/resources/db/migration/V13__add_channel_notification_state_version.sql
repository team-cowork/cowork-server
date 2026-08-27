ALTER TABLE account_channel_notification
    ADD COLUMN state_occurred_at TIMESTAMPTZ;

UPDATE account_channel_notification
SET state_occurred_at = updated_at;

ALTER TABLE account_channel_notification
    ALTER COLUMN state_occurred_at SET NOT NULL,
    ALTER COLUMN state_occurred_at SET DEFAULT clock_timestamp();

COMMENT ON COLUMN account_channel_notification.state_occurred_at IS
    'preference.channel-notification.changed authoritative monotonic version';

DROP TRIGGER trg_acn_updated_at ON account_channel_notification;

CREATE FUNCTION set_channel_notification_state_occurred_at()
    RETURNS TRIGGER AS
$$
DECLARE
    next_version TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'INSERT' THEN
        next_version := clock_timestamp();
    ELSE
        next_version := GREATEST(clock_timestamp(), OLD.state_occurred_at + INTERVAL '1 microsecond');
    END IF;
    NEW.state_occurred_at := next_version;
    NEW.updated_at := next_version;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_acn_state_occurred_at
    BEFORE INSERT OR UPDATE ON account_channel_notification
    FOR EACH ROW EXECUTE FUNCTION set_channel_notification_state_occurred_at();
