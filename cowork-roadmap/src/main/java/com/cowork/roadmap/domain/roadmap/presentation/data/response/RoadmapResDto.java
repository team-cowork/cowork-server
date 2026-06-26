package com.cowork.roadmap.domain.roadmap.presentation.data.response;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

public record RoadmapResDto(Long id, String title, String description, String category, String scope, Long ownerTeamId,
        Long ownerProjectId, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
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
