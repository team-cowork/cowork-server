-- DataGSM webhook 이벤트를 변하지 않는 student id로 조인하기 위한 컬럼 추가.
-- 이메일은 변경될 수 있어 조인 키로 부적합하므로, 불변인 DataGSM student id를 사용한다.
ALTER TABLE tb_accounts
    ADD COLUMN datagsm_student_id BIGINT DEFAULT NULL COMMENT 'DataGSM의 student.id' AFTER student_number,
    ADD UNIQUE KEY uq_tb_accounts_datagsm_student_id (datagsm_student_id);
