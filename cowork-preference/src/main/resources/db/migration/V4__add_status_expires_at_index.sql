CREATE INDEX idx_rs_status_expires_at
    ON resource_setting (((settings->>'status_expires_at')::TIMESTAMPTZ))
    WHERE resource_type = 'ACCOUNT'
      AND settings->>'status_expires_at' IS NOT NULL;
