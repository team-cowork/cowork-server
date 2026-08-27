CREATE TABLE tb_github_repo_setting_tombstones
(
    repo_id            BIGINT      NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tb_github_repo_setting_tombstones PRIMARY KEY (repo_id)
);

COMMENT ON COLUMN tb_github_repo_setting_tombstones.repo_id IS 'cowork-project의 GitHub repository id';

CREATE OR REPLACE FUNCTION set_resource_setting_state_version()
    RETURNS TRIGGER AS
$$
DECLARE
    next_version TIMESTAMPTZ;
BEGIN
    next_version = GREATEST(
        clock_timestamp(),
        COALESCE(NEW.state_occurred_at, '-infinity'::timestamptz)
    );
    IF TG_OP = 'UPDATE' THEN
        next_version = GREATEST(next_version, OLD.state_occurred_at + INTERVAL '1 microsecond');
    END IF;
    NEW.updated_at = next_version;
    NEW.state_occurred_at = next_version;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
