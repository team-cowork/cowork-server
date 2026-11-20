-- Wait for pre-cutover DataGSM writes to commit. Earlier user binaries cannot
-- upsert by a provider number once it stops being the Cowork primary key.
ALTER TABLE tb_accounts RENAME COLUMN datagsm_student_id TO legacy_datagsm_student_id;

START TRANSACTION;
INSERT INTO tb_account_identities (account_id, provider, provider_subject)
SELECT id, 'DATAGSM', CAST(id AS CHAR) FROM tb_accounts a
WHERE a.sex IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tb_account_identities i
    WHERE i.account_id = a.id AND i.provider = 'DATAGSM' AND i.provider_subject = CAST(a.id AS CHAR));

UPDATE tb_gsm_student_info s JOIN tb_accounts a ON a.id = s.account_id
SET s.datagsm_student_id = a.legacy_datagsm_student_id, s.sex = a.sex, s.student_role = a.student_role,
    s.student_number = a.student_number, s.major = a.major, s.specialty = a.specialty, s.datagsm_updated_at = a.datagsm_updated_at
WHERE a.sex IS NOT NULL;
INSERT INTO tb_gsm_student_info (account_id, datagsm_student_id, sex, student_role, student_number, major, specialty, datagsm_updated_at)
SELECT id, legacy_datagsm_student_id, sex, student_role, student_number, major, specialty, datagsm_updated_at
FROM tb_accounts a WHERE a.sex IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tb_gsm_student_info s WHERE s.account_id = a.id);
COMMIT;

DROP PROCEDURE sync_legacy_datagsm_accounts;
ALTER TABLE tb_user_identity_command_inbox
    MODIFY user_id BIGINT NULL COMMENT 'cowork-user.tb_accounts.id. 실패한 v3 명령은 NULL';
