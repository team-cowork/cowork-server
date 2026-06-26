package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

import reactor.core.publisher.Flux;

public interface ListMyRoadmapAssignmentsService {

    Flux<AssignmentResDto> execute(Long userId);
}
