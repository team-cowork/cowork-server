package com.cowork.roadmap.domain.node.presentation.data.response;

import java.time.LocalDateTime;
import java.util.List;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

public record NodeResponse(Long id, Long roadmapId, Long parentId, String title, String content, String sourceUrl,
        String sourceTitle, Integer position, List<NodeReferenceResponse> references, LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static NodeResponse of(RoadmapNode node, List<NodeReferenceResponse> references) {
        return new NodeResponse(node.getId(),
                node.getRoadmapId(),
                node.getParentId(),
                node.getTitle(),
                node.getContent(),
                node.getSourceUrl(),
                node.getSourceTitle(),
                node.getPosition(),
                references,
                node.getCreatedAt(),
                node.getUpdatedAt());
    }
}
