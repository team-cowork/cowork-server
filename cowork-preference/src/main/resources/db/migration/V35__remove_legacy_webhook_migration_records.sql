DELETE FROM tb_webhook_operations
WHERE actor_id = 0
  AND (
      idempotency_key LIKE 'legacy-webhook:%'
      OR idempotency_key LIKE 'legacy-webhook-manifest-chunk:%'
      OR idempotency_key LIKE 'legacy-webhook-manifest-complete:%'
  );

DELETE FROM tb_projection_consumer_checkpoints
WHERE consumer_group = 'cowork-preference-webhook-legacy-projection'
  AND topic = 'channel.webhook-legacy.state.v1';

DELETE FROM tb_projection_consumer_barriers
WHERE consumer_group = 'cowork-preference-webhook-legacy-projection'
  AND topic = 'channel.webhook-legacy.state.v1';

DELETE FROM tb_projection_quarantine
WHERE consumer_group = 'cowork-preference-webhook-legacy-projection'
  AND topic = 'channel.webhook-legacy.state.v1';
