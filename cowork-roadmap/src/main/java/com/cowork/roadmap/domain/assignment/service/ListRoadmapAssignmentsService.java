package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

import reactor.core.publisher.Flux;

public interface ListRoadmapAssignmentsService {

    Flux<AssignmentResDto> execute(Long userId, String userRole, Long roadmapId);
}
