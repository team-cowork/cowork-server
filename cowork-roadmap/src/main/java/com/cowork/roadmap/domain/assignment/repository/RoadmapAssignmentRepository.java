package com.cowork.roadmap.domain.assignment.repository;

import java.util.Collection;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapAssignmentRepository extends R2dbcRepository<RoadmapAssignment, Long> {

    Flux<RoadmapAssignment> findByAssigneeUserIdOrderByIdDesc(Long assigneeUserId);

    Flux<RoadmapAssignment> findByRoadmapIdOrderByIdDesc(Long roadmapId);

    /** 잠금 읽기로 transaction snapshot 이후 커밋된 과제까지 센다. */
    @Query("SELECT COUNT(*) FROM tb_roadmap_assignments WHERE roadmap_id = :roadmapId AND node_id IN (:nodeIds) FOR SHARE")
    Mono<Long> countByRoadmapIdAndNodeIdInForShare(Long roadmapId, Collection<Long> nodeIds);
}
