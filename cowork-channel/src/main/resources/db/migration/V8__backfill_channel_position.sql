UPDATE tb_channels c
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY created_at, id) - 1 AS new_position
    FROM tb_channels
) ranked ON ranked.id = c.id
JOIN (
    SELECT team_id
    FROM tb_channels
    GROUP BY team_id
    HAVING MAX(position) = 0
) untouched ON untouched.team_id = c.team_id
SET c.position = ranked.new_position;
