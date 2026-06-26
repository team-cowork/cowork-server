package com.cowork.roadmap.domain.roadmap.presentation.data.response;

import java.util.List;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeTreeResDto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 로드맵 메타 + 노드 트리(루트 노드부터 중첩). */
public record RoadmapTreeResDto(

        @Schema(description = "로드맵 메타 정보") RoadmapResDto roadmap,

        @Schema(description = "루트부터 중첩된 노드 트리") List<NodeTreeResDto> nodes) {
}
