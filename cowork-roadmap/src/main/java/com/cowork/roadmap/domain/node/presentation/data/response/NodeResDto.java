package com.cowork.roadmap.domain.node.presentation.data.response;

import java.time.LocalDateTime;
import java.util.List;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

import io.swagger.v3.oas.annotations.media.Schema;

public record NodeResDto(

        @Schema(description = "노드 ID") Long id,

        @Schema(description = "소속 로드맵 ID") Long roadmapId,

        @Schema(description = "상위 노드 ID (루트면 null)") Long parentId,

        @Schema(description = "노드 제목") String title,

        @Schema(description = "본문 (마크다운)") String content,

        @Schema(description = "원본 문서 URL") String sourceUrl,

        @Schema(description = "원본 문서 제목") String sourceTitle,

        @Schema(description = "형제 노드 내 정렬 순서") Integer position,

        @Schema(description = "관련 자료 목록") List<NodeReferenceResDto> references,

        @Schema(description = "생성 일시", type = "string", example = "2025-03-02T00:00:00") LocalDateTime createdAt,

        @Schema(description = "수정 일시", type = "string", example = "2025-03-02T00:00:00") LocalDateTime updatedAt) {

    public static NodeResDto of(RoadmapNode node, List<NodeReferenceResDto> references) {
        return new NodeResDto(node.getId(),
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
