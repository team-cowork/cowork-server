-- Keep the most recently registered owner for each token. A larger id wins when
-- two registrations have the same updated_at value. Deleting the older row also
-- invalidates its device_token_id generation for the delivery retry worker.
DELETE stale
FROM tb_device_token stale
JOIN tb_device_token latest
  ON stale.token = latest.token
 AND (
      stale.updated_at < latest.updated_at
      OR (stale.updated_at = latest.updated_at AND stale.id < latest.id)
 )
;

ALTER TABLE tb_device_token
    DROP INDEX uq_tb_device_token_account_token,
    ADD UNIQUE KEY uq_tb_device_token_token (token);
