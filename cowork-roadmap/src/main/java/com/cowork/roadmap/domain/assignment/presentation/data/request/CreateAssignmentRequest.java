package com.cowork.roadmap.domain.assignment.presentation.data.request;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record CreateAssignmentRequest(

        @Schema(description = "할당할 로드맵 ID") @NotNull Long roadmapId,

        @Schema(description = "특정 노드(서브트리) 할당 시 노드 ID. null이면 로드맵 전체") Long nodeId,

        @Schema(description = "할당 맥락", example = "TEAM", allowableValues = {
                "TEAM", "PROJECT"}) @NotNull RoadmapScope scope,

        @Schema(description = "팀 ID (권한 검증 기준). cowork-team의 tb_teams.id") @NotNull Long teamId,

        @Schema(description = "프로젝트 ID (PROJECT 스코프에서 필수)") Long projectId,

        @Schema(description = "온보딩 대상 사용자 ID") @NotNull Long assigneeUserId,

        @Schema(description = "마감 일시 (선택)") LocalDateTime dueDate){
}
