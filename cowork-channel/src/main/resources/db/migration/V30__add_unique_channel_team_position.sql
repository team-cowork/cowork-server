-- 동시 생성·재정렬로 position이 겹친 팀만 (position, id) 순서로 0부터 다시 매긴다. 겹침이 없는 팀은 그대로 둔다.
-- event state도 같은 값과 다음 버전으로 맞춰 시작 snapshot이 consumer projection에 바뀐 position을 전달하게 한다.
-- DM 채널은 team_id가 NULL이라 유일성 판정에서 제외된다.
UPDATE tb_channel_event_states state
JOIN (
    SELECT
        id,
        team_id,
        position,
        ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY position, id) - 1 AS new_position
    FROM tb_channels
) ranked ON ranked.id = state.channel_id
JOIN (
    SELECT team_id
    FROM tb_channels
    WHERE team_id IS NOT NULL
    GROUP BY team_id
    HAVING COUNT(*) <> COUNT(DISTINCT position)
) duplicated ON duplicated.team_id = ranked.team_id
SET state.position = ranked.new_position,
    state.state_occurred_at = GREATEST(state.state_occurred_at + INTERVAL 1 MICROSECOND, CURRENT_TIMESTAMP(6))
WHERE state.deleted = FALSE
  AND ranked.position <> ranked.new_position;

UPDATE tb_channels c
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY position, id) - 1 AS new_position
    FROM tb_channels
) ranked ON ranked.id = c.id
JOIN (
    SELECT team_id
    FROM tb_channels
    WHERE team_id IS NOT NULL
    GROUP BY team_id
    HAVING COUNT(*) <> COUNT(DISTINCT position)
) duplicated ON duplicated.team_id = c.team_id
SET c.position = ranked.new_position
WHERE c.position <> ranked.new_position;

-- InnoDB 보조 인덱스는 PK(id)를 포함하므로 (team_id, position) 유일 인덱스가 기존 (team_id, position, id) 정렬 조회를 대신한다.
ALTER TABLE tb_channels
    DROP INDEX idx_tb_channels_team_position_id,
    ADD UNIQUE INDEX uq_tb_channels_team_id_position (team_id, position);
