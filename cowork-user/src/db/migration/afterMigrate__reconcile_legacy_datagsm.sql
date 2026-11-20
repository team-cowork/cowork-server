-- Flyway runs before the single cowork-user allocation starts its consumers.
-- Also catches logins and webhooks committed by a rolled-back application.
START TRANSACTION;
CALL sync_legacy_datagsm_accounts(NULL);
COMMIT;
