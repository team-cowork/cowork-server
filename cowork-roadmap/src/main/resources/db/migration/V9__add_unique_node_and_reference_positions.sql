-- 동시 요청으로 position이 겹친 형제 노드와 참고 자료만 (position, id) 순서로 0부터 다시 매긴다. 겹침이 없는 scope는 그대로 둔다.
-- 형제 scope는 (roadmap_id, parent_id)이며 루트 노드는 parent_id가 NULL이라 COALESCE(parent_id, 0)로 묶는다(id는 0이 될 수 없다).
UPDATE tb_roadmap_nodes n
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY roadmap_id, parent_id ORDER BY position, id) - 1 AS new_position
    FROM tb_roadmap_nodes
) ranked ON ranked.id = n.id
JOIN (
    SELECT roadmap_id, COALESCE(parent_id, 0) AS parent_key
    FROM tb_roadmap_nodes
    GROUP BY roadmap_id, COALESCE(parent_id, 0)
    HAVING COUNT(*) <> COUNT(DISTINCT position)
) duplicated ON duplicated.roadmap_id = n.roadmap_id AND duplicated.parent_key = COALESCE(n.parent_id, 0)
SET n.position = ranked.new_position
WHERE n.position <> ranked.new_position;

UPDATE tb_roadmap_node_references r
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (PARTITION BY node_id ORDER BY position, id) - 1 AS new_position
    FROM tb_roadmap_node_references
) ranked ON ranked.id = r.id
JOIN (
    SELECT node_id
    FROM tb_roadmap_node_references
    GROUP BY node_id
    HAVING COUNT(*) <> COUNT(DISTINCT position)
) duplicated ON duplicated.node_id = r.node_id
SET r.position = ranked.new_position
WHERE r.position <> ranked.new_position;

-- MySQL 유니크 인덱스는 NULL끼리 충돌하지 않으므로 루트 노드도 판정하도록 NULL을 0으로 치환한 생성 컬럼에 유니크를 건다.
ALTER TABLE tb_roadmap_nodes
    ADD COLUMN parent_key BIGINT AS (COALESCE(parent_id, 0)) STORED COMMENT 'parent_id NULL을 0으로 치환한 유니크 판정용 값',
    ADD UNIQUE INDEX uq_tb_roadmap_nodes_roadmap_parent_key_position (roadmap_id, parent_key, position);

-- node_id 외래 키가 새 유니크 인덱스를 쓰도록 먼저 추가한 뒤 기존 인덱스를 지운다.
ALTER TABLE tb_roadmap_node_references
    ADD UNIQUE INDEX uq_tb_roadmap_node_references_node_id_position (node_id, position);

ALTER TABLE tb_roadmap_node_references
    DROP INDEX idx_tb_roadmap_node_references_node_id;
