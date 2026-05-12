UPDATE tb_projects p
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY created_at, id) - 1 AS new_position
    FROM tb_projects
) ranked ON ranked.id = p.id
JOIN (
    SELECT team_id
    FROM tb_projects
    GROUP BY team_id
    HAVING MAX(position) = 0
) untouched ON untouched.team_id = p.team_id
SET p.position = ranked.new_position;
