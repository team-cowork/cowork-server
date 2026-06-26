package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

import reactor.core.publisher.Mono;

public interface ModifyRoadmapAssignmentStatusService {

    Mono<AssignmentResDto> execute(Long userId,
            String userRole,
            Long assignmentId,
            UpdateAssignmentStatusReqDto request);
}
