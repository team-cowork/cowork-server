-- Deploy with old roadmap writers stopped. due_date now stores UTC like created_at and updated_at.
-- Clients sent offset-less Asia/Seoul wall-clock values before this version, so shift them to UTC.
UPDATE tb_roadmap_assignments
SET due_date = due_date - INTERVAL 9 HOUR
WHERE due_date IS NOT NULL;
