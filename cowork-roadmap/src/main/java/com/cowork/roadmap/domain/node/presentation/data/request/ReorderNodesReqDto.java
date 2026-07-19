package com.cowork.roadmap.domain.node.presentation.data.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

public record ReorderNodesReqDto(

        @Schema(description = "정렬 대상 부모 노드 ID (루트 형제 정렬이면 null)") Long parentId,

        @Schema(description = "원하는 순서대로 나열한 노드 ID 목록 (해당 부모의 모든 자식을 정확히 포함)") @NotEmpty List<Long> orderedNodeIds) {
}
