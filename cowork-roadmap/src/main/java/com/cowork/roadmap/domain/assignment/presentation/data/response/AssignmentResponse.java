package com.cowork.roadmap.domain.assignment.presentation.data.response;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

public record AssignmentResponse(Long id, Long roadmapId, Long nodeId, String scope, Long teamId, Long projectId,
        Long assigneeUserId, Long assignedBy, String status, LocalDateTime dueDate, LocalDateTime createdAt) {
    public static AssignmentResponse from(RoadmapAssignment assignment) {
        return new AssignmentResponse(assignment.getId(),
                assignment.getRoadmapId(),
                assignment.getNodeId(),
                assignment.getScope(),
                assignment.getTeamId(),
                assignment.getProjectId(),
                assignment.getAssigneeUserId(),
                assignment.getAssignedBy(),
                assignment.getStatus(),
                assignment.getDueDate(),
                assignment.getCreatedAt());
    }
}
