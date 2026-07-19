package com.cowork.roadmap.domain.node.presentation.data.response;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;

import io.swagger.v3.oas.annotations.media.Schema;

public record NodeReferenceResDto(

        @Schema(description = "관련 자료 ID") Long id,

        @Schema(description = "소속 노드 ID") Long nodeId,

        @Schema(description = "관련 자료 제목") String title,

        @Schema(description = "관련 자료 링크") String url,

        @Schema(description = "정렬 순서") Integer position) {

    public static NodeReferenceResDto from(RoadmapNodeReference ref) {
        return new NodeReferenceResDto(ref.getId(), ref.getNodeId(), ref.getTitle(), ref.getUrl(), ref.getPosition());
    }
}
