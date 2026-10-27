package com.cowork.roadmap.domain.assignment.service;

import java.util.List;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

public interface ListRoadmapAssignmentsService {

    List<AssignmentResDto> execute(Long userId, String userRole, Long roadmapId);
}
