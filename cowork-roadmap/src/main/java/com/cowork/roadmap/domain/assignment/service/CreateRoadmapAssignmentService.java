package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

public interface CreateRoadmapAssignmentService {

    AssignmentResDto execute(Long userId, String userRole, CreateAssignmentReqDto request);
}
