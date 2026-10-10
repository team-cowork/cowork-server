package com.cowork.roadmap.domain.node.repository;

import java.util.Collection;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapNodeReferenceRepository extends R2dbcRepository<RoadmapNodeReference, Long> {

    Flux<RoadmapNodeReference> findByNodeIdOrderByPositionAsc(Long nodeId);

    Flux<RoadmapNodeReference> findByNodeIdInOrderByNodeIdAscPositionAsc(Collection<Long> nodeIds);

    /** 삭제로 생긴 gap과 무관하게 기존 최댓값 다음 position을 반환한다. */
    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM tb_roadmap_node_references WHERE node_id = :nodeId")
    Mono<Long> findNextPosition(Long nodeId);
}
