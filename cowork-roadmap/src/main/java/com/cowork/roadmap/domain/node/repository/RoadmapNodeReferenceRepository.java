package com.cowork.roadmap.domain.node.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

public interface RoadmapNodeReferenceRepository extends JpaRepository<RoadmapNodeReference, Long> {

    List<RoadmapNodeReference> findByNodeIdOrderByPositionAsc(Long nodeId);

    List<RoadmapNodeReference> findByNodeIdInOrderByNodeIdAscPositionAsc(Collection<Long> nodeIds);

    /** 삭제로 생긴 gap과 무관하게 기존 최댓값 다음 position을 반환한다. */
    @Query(value = "SELECT COALESCE(MAX(position) + 1, 0) FROM tb_roadmap_node_references WHERE node_id = :nodeId", nativeQuery = true)
    Long findNextPosition(Long nodeId);
}
