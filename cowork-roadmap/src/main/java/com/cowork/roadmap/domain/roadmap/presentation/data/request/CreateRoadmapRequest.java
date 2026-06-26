package com.cowork.roadmap.domain.roadmap.presentation.data.request;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoadmapRequest(

        @Schema(description = "로드맵 제목", example = "Flutter 온보딩 로드맵") @NotBlank @Size(max = 150) String title,

        @Schema(description = "로드맵 설명") @Size(max = 1000) String description,

        @Schema(description = "종류/전공/포지션", example = "Flutter") @NotBlank @Size(max = 50) String category,

        @Schema(description = "범위", example = "GLOBAL") @NotNull RoadmapScope scope,

        @Schema(description = "소유 팀 ID (TEAM/PROJECT 스코프에서 필수). cowork-team의 tb_teams.id") Long ownerTeamId,

        @Schema(description = "소유 프로젝트 ID (PROJECT 스코프). cowork-project의 tb_projects.id") Long ownerProjectId) {
}
