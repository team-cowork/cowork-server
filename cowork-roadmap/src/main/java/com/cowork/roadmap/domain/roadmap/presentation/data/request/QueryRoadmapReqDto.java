package com.cowork.roadmap.domain.roadmap.presentation.data.request;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;

import io.swagger.v3.oas.annotations.media.Schema;

public record QueryRoadmapReqDto(

        @Schema(description = "범위 (미지정 시 GLOBAL)", example = "GLOBAL") RoadmapScope scope,

        @Schema(description = "종류/전공/포지션 필터", example = "Flutter") String category,

        @Schema(description = "소유 팀 ID (커스텀 로드맵 조회). cowork-team의 tb_teams.id") Long teamId,

        @Schema(description = "소유 프로젝트 ID (커스텀 로드맵 조회). cowork-project의 tb_projects.id") Long projectId) {
}
