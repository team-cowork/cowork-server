package com.cowork.roadmap.domain.roadmap.presentation.data.response;

import java.time.LocalDateTime;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

public record RoadmapResponse(Long id, String title, String description, String category, String scope,
        Long ownerTeamId, Long ownerProjectId, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static RoadmapResponse from(Roadmap roadmap) {
        return new RoadmapResponse(roadmap.getId(),
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
