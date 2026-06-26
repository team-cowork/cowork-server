package com.cowork.roadmap.domain.assignment.presentation.data.response;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;

import io.swagger.v3.oas.annotations.media.Schema;

public record AssignmentResDto(

        @Schema(description = "할당 ID") Long id,

        @Schema(description = "대상 로드맵 ID") Long roadmapId,

        @Schema(description = "특정 노드 할당 시 노드 ID (전체 로드맵이면 null)") Long nodeId,

        @Schema(description = "할당 맥락", example = "TEAM") String scope,

        @Schema(description = "소유 팀 ID") Long teamId,

        @Schema(description = "소유 프로젝트 ID") Long projectId,

        @Schema(description = "온보딩 대상 사용자 ID") Long assigneeUserId,

        @Schema(description = "할당한 사용자 ID") Long assignedBy,

        @Schema(description = "진행 상태", example = "IN_PROGRESS") String status,

        @Schema(description = "마감 일시", type = "string", example = "2025-03-02T00:00:00") LocalDateTime dueDate,

        @Schema(description = "생성 일시", type = "string", example = "2025-03-02T00:00:00") LocalDateTime createdAt) {

    public static AssignmentResDto from(RoadmapAssignment assignment) {
        return new AssignmentResDto(assignment.getId(),
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
