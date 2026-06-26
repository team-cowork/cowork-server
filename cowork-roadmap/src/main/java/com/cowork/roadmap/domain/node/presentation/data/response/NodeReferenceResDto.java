package com.cowork.roadmap.domain.node.presentation.data.response;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

public record NodeReferenceResDto(Long id, Long nodeId, String title, String url, Integer position) {
    public static NodeReferenceResDto from(RoadmapNodeReference ref) {
        return new NodeReferenceResDto(ref.getId(), ref.getNodeId(), ref.getTitle(), ref.getUrl(), ref.getPosition());
    }
}
