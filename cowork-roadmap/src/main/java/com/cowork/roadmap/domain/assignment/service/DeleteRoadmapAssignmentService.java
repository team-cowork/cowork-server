package com.cowork.roadmap.domain.assignment.service;

import reactor.core.publisher.Mono;

public interface DeleteRoadmapAssignmentService {

    Mono<Void> execute(Long userId, String userRole, Long assignmentId);
}
