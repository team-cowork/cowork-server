-- 로드맵 목록 조회는 scope를 선행 조건으로 사용한다(findByScope..., findByScopeAndCategory...).
-- 기존 (category, scope) 순서로는 scope 단독 필터를 인덱스로 처리할 수 없어 (scope, category)로 재정렬한다.
ALTER TABLE tb_roadmaps
    DROP INDEX idx_tb_roadmaps_category_scope,
    ADD INDEX idx_tb_roadmaps_scope_category (scope, category);

-- node_id가 NULL인 로드맵 전체 할당은 MySQL의 NULL 비교 특성상 유니크 제약이 동작하지 않아 중복 생성이 가능했다.
-- NULL을 0으로 치환한 생성 컬럼으로 유니크를 걸어 동일 (로드맵, 노드, 대상자) 중복 할당을 막는다.
-- (id는 AUTO_INCREMENT라 0 값과 충돌하지 않는다.)
ALTER TABLE tb_roadmap_assignments
    DROP INDEX uq_tb_roadmap_assignments_roadmap_node_assignee,
    ADD COLUMN node_key BIGINT AS (COALESCE(node_id, 0)) STORED COMMENT 'node_id NULL을 0으로 치환한 유니크 판정용 값',
    ADD UNIQUE uq_tb_roadmap_assignments_roadmap_node_key_assignee (roadmap_id, node_key, assignee_user_id);
