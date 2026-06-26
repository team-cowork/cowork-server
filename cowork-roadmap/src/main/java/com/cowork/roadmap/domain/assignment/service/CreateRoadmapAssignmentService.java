package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

import reactor.core.publisher.Mono;

public interface CreateRoadmapAssignmentService {

    Mono<AssignmentResDto> execute(Long userId, String userRole, CreateAssignmentReqDto request);
}
