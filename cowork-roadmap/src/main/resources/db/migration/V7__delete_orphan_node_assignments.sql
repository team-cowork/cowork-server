-- 노드 삭제가 과제를 정리하지 않던 시기에 남은, 존재하지 않는 노드를 가리키는 과제를 삭제한다.
-- node_key(STORED 생성 컬럼)의 기반 컬럼이라 node_id에는 CASCADE FK를 둘 수 없으므로 이후 무결성은 애플리케이션이 보장한다.
DELETE a
FROM tb_roadmap_assignments a
         LEFT JOIN tb_roadmap_nodes n ON n.id = a.node_id
WHERE a.node_id IS NOT NULL
  AND n.id IS NULL;
