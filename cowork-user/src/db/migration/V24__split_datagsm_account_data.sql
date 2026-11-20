-- Existing DataGSM commands still supply the account ID. New account types will
-- use the same allocator after the authorization command contract is switched.
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

-- During the compatibility release both generations write the legacy columns.
-- Copy by account_id only. A student ID owned by another account must fail.
DELIMITER //
CREATE PROCEDURE sync_legacy_datagsm_accounts(IN target_id BIGINT)
SQL SECURITY INVOKER
BEGIN
    INSERT INTO tb_account_identities (account_id, provider, provider_subject)
    SELECT a.id, 'DATAGSM', CAST(a.id AS CHAR)
    FROM tb_accounts a
    WHERE a.sex IS NOT NULL AND (target_id IS NULL OR a.id = target_id)
      AND NOT EXISTS (SELECT 1 FROM tb_account_identities i
                      WHERE i.provider = 'DATAGSM' AND i.provider_subject = CAST(a.id AS CHAR) AND i.account_id = a.id);

    UPDATE tb_gsm_student_info s
    JOIN tb_accounts a ON a.id = s.account_id
    SET s.datagsm_student_id = a.datagsm_student_id, s.sex = a.sex, s.student_role = a.student_role,
        s.student_number = a.student_number, s.major = a.major, s.specialty = a.specialty,
        s.datagsm_updated_at = a.datagsm_updated_at
    WHERE a.sex IS NOT NULL AND (target_id IS NULL OR a.id = target_id);

    INSERT INTO tb_gsm_student_info (account_id, datagsm_student_id, sex, student_role, student_number, major, specialty, datagsm_updated_at)
    SELECT a.id, a.datagsm_student_id, a.sex, a.student_role, a.student_number, a.major, a.specialty, a.datagsm_updated_at
    FROM tb_accounts a
    WHERE a.sex IS NOT NULL AND (target_id IS NULL OR a.id = target_id)
      AND NOT EXISTS (SELECT 1 FROM tb_gsm_student_info s WHERE s.account_id = a.id);
END//
DELIMITER ;
