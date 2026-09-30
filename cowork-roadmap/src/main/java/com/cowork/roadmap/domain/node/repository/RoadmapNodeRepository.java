package com.cowork.roadmap.domain.node.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapNodeRepository extends R2dbcRepository<RoadmapNode, Long> {

    Flux<RoadmapNode> findByRoadmapIdOrderByPositionAsc(Long roadmapId);

    Mono<Long> countByRoadmapIdAndParentIdIsNull(Long roadmapId);

    Mono<Long> countByRoadmapIdAndParentId(Long roadmapId, Long parentId);
}
