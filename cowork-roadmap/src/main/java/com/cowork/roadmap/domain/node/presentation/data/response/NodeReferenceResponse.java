package com.cowork.roadmap.domain.node.presentation.data.response;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

public record NodeReferenceResponse(Long id, Long nodeId, String title, String url, Integer position) {
    public static NodeReferenceResponse from(RoadmapNodeReference ref) {
        return new NodeReferenceResponse(ref.getId(), ref.getNodeId(), ref.getTitle(), ref.getUrl(), ref.getPosition());
    }
}
