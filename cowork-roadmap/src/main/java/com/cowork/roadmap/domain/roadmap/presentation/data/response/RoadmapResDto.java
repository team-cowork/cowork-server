package com.cowork.roadmap.domain.roadmap.presentation.data.response;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

import io.swagger.v3.oas.annotations.media.Schema;

public record RoadmapResDto(

        @Schema(description = "로드맵 ID") Long id,

        @Schema(description = "로드맵 제목") String title,

        @Schema(description = "로드맵 설명") String description,

        @Schema(description = "종류/전공/포지션") String category,

        @Schema(description = "범위", example = "GLOBAL") String scope,

        @Schema(description = "소유 팀 ID") Long ownerTeamId,

        @Schema(description = "소유 프로젝트 ID") Long ownerProjectId,

        @Schema(description = "생성자 사용자 ID") Long createdBy,

        @Schema(description = "생성 일시") LocalDateTime createdAt,

        @Schema(description = "수정 일시") LocalDateTime updatedAt) {

    public static RoadmapResDto from(Roadmap roadmap) {
        return new RoadmapResDto(roadmap.getId(),
                roadmap.getTitle(),
                roadmap.getDescription(),
                roadmap.getCategory(),
                roadmap.getScope(),
                roadmap.getOwnerTeamId(),
                roadmap.getOwnerProjectId(),
                roadmap.getCreatedBy(),
                roadmap.getCreatedAt(),
                roadmap.getUpdatedAt());
    }
}
