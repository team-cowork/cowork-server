-- MySQL requires foreign_key_checks off to change the auto-increment attribute
-- on the referenced PK. The type and all key values stay the same.
SET @previous_fk_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;
ALTER TABLE tb_accounts
    MODIFY id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Cowork 사용자 ID',
    MODIFY sex VARCHAR(10) NULL;
SET SESSION foreign_key_checks = @previous_fk_checks;

CREATE TABLE tb_account_identities
(
    account_id       BIGINT NOT NULL,
    provider         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (provider, provider_subject),
    UNIQUE KEY uq_tb_account_identities_account_id_provider (account_id, provider),
    CONSTRAINT fk_tb_account_identities_account FOREIGN KEY (account_id) REFERENCES tb_accounts (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE tb_gsm_student_info
(
    account_id        BIGINT NOT NULL PRIMARY KEY,
    -- Accounts created before V6 can lack a verified student ID until the next login.
    datagsm_student_id BIGINT NULL,
    sex                VARCHAR(10) NOT NULL,
    student_role       VARCHAR(50) NULL,
    student_number     VARCHAR(30) NULL,
    major              VARCHAR(50) NULL,
    specialty          VARCHAR(255) NULL,
    datagsm_updated_at DATETIME(6) NULL,
    UNIQUE KEY uq_tb_gsm_student_info_datagsm_student_id (datagsm_student_id),
    INDEX idx_tb_gsm_student_info_student_number (student_number),
    INDEX idx_tb_gsm_student_info_major_student_role (major, student_role),
    INDEX idx_tb_gsm_student_info_student_role (student_role),
    CONSTRAINT fk_tb_gsm_student_info_account FOREIGN KEY (account_id) REFERENCES tb_accounts (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Wait for pre-cutover DataGSM writes to commit. Earlier user binaries cannot
-- upsert by a provider number once it stops being the Cowork primary key.
ALTER TABLE tb_accounts RENAME COLUMN datagsm_student_id TO legacy_datagsm_student_id;

START TRANSACTION;
INSERT INTO tb_account_identities (account_id, provider, provider_subject)
SELECT id, 'DATAGSM', CAST(id AS CHAR)
FROM tb_accounts
WHERE sex IS NOT NULL;

INSERT INTO tb_gsm_student_info (account_id, datagsm_student_id, sex, student_role, student_number, major, specialty, datagsm_updated_at)
SELECT id, legacy_datagsm_student_id, sex, student_role, student_number, major, specialty, datagsm_updated_at
FROM tb_accounts
WHERE sex IS NOT NULL;
COMMIT;

ALTER TABLE tb_user_identity_command_inbox
    MODIFY user_id BIGINT NULL COMMENT 'cowork-user.tb_accounts.id. 실패한 v3 명령은 NULL';
