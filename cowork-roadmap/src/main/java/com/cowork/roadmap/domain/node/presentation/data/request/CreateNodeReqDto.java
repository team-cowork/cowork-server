package com.cowork.roadmap.domain.node.presentation.data.request;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNodeReqDto(

        @Schema(description = "상위 노드 ID (루트면 null)") Long parentId,

        @Schema(description = "노드/문서 제목", example = "Dart로 AI 만들기") @NotBlank @Size(max = 200) String title,

        @Schema(description = "번역된 본문 (마크다운). 길이는 UTF-16 코드 단위로 세므로 이모지 등 일부 문자는 2자로 센다") @Size(max = RoadmapNode.MAX_CONTENT_LENGTH, message = "노드 본문은 {max}자 이하여야 합니다.") String content,

        @Schema(description = "원본 문서 URL") @Size(max = 500) String sourceUrl,

        @Schema(description = "원본 문서 제목") @Size(max = 300) String sourceTitle) {
}
