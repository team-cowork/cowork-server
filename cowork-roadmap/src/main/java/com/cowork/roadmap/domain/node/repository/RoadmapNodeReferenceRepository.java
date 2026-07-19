package com.cowork.roadmap.domain.node.repository;

import java.util.Collection;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapNodeReferenceRepository extends R2dbcRepository<RoadmapNodeReference, Long> {

    Flux<RoadmapNodeReference> findByNodeIdOrderByPositionAsc(Long nodeId);

    Flux<RoadmapNodeReference> findByNodeIdInOrderByNodeIdAscPositionAsc(Collection<Long> nodeIds);

    Mono<Long> countByNodeId(Long nodeId);
}
