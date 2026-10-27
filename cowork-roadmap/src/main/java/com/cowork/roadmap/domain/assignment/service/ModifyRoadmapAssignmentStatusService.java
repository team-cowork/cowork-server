package com.cowork.roadmap.domain.assignment.service;

import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;

public interface ModifyRoadmapAssignmentStatusService {

    AssignmentResDto execute(Long userId, String userRole, Long assignmentId, UpdateAssignmentStatusReqDto request);
}
