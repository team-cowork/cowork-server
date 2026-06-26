package com.cowork.roadmap.domain.assignment.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

import reactor.core.publisher.Flux;

public interface RoadmapAssignmentRepository extends R2dbcRepository<RoadmapAssignment, Long> {

    Flux<RoadmapAssignment> findByAssigneeUserIdOrderByIdDesc(Long assigneeUserId);

    Flux<RoadmapAssignment> findByRoadmapIdOrderByIdDesc(Long roadmapId);
}
