package com.cowork.roadmap.domain.roadmap.presentation.data.response;

import java.util.List;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeTreeResponse;

/** 로드맵 메타 + 노드 트리(루트 노드부터 중첩). */
public record RoadmapTreeResponse(RoadmapResponse roadmap, List<NodeTreeResponse> nodes) {
}
