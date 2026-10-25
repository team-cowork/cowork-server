-- 동시 생성·재정렬로 position이 겹친 팀만 (position, id) 순서로 0부터 다시 매긴다. 겹침이 없는 팀은 그대로 둔다.
-- 바뀐 행은 다음 state 버전으로 올려 시작 snapshot이 consumer projection에 바뀐 position을 전달하게 한다.
UPDATE tb_projects p
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY position, id) - 1 AS new_position
    FROM tb_projects
) ranked ON ranked.id = p.id
JOIN (
    SELECT team_id
    FROM tb_projects
    GROUP BY team_id
    HAVING COUNT(*) <> COUNT(DISTINCT position)
) duplicated ON duplicated.team_id = p.team_id
SET p.position = ranked.new_position,
    p.state_occurred_at = GREATEST(p.state_occurred_at + INTERVAL 1 MICROSECOND, CURRENT_TIMESTAMP(6))
WHERE p.position <> ranked.new_position;

ALTER TABLE tb_projects
    DROP INDEX idx_tb_projects_team_position,
    ADD UNIQUE INDEX uq_tb_projects_team_id_position (team_id, position);
