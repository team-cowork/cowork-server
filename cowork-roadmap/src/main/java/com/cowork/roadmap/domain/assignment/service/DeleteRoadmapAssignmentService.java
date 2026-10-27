package com.cowork.roadmap.domain.assignment.service;

public interface DeleteRoadmapAssignmentService {

    void execute(Long userId, String userRole, Long assignmentId);
}
