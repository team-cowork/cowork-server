package com.cowork.roadmap.domain.node.presentation.data.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 트리 조회용 노드. children에 하위 노드가 재귀적으로 중첩된다. */
public record NodeTreeResDto(

        @Schema(description = "노드 ID") Long id,

        @Schema(description = "상위 노드 ID (루트면 null)") Long parentId,

        @Schema(description = "노드 제목") String title,

        @Schema(description = "본문 (마크다운)") String content,

        @Schema(description = "원본 문서 URL") String sourceUrl,

        @Schema(description = "원본 문서 제목") String sourceTitle,

        @Schema(description = "형제 노드 내 정렬 순서") Integer position,

        @Schema(description = "관련 자료 목록") List<NodeReferenceResDto> references,

        @Schema(description = "하위 노드 목록 (재귀)") List<NodeTreeResDto> children) {
}
