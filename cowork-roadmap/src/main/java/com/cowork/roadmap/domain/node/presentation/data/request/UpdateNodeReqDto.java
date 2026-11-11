package com.cowork.roadmap.domain.node.presentation.data.request;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.global.validation.HttpUrl;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** PATCH 의미: null인 필드는 변경하지 않는다. */
public record UpdateNodeReqDto(

        @Schema(description = "노드/문서 제목. 빈 문자열과 공백만 있는 값은 거부") @Size(max = 200) String title,

        @Schema(description = "번역된 본문 (마크다운). 길이는 UTF-16 코드 단위로 세므로 이모지 등 일부 문자는 2자로 센다") @Size(max = RoadmapNode.MAX_CONTENT_LENGTH, message = "노드 본문은 {max}자 이하여야 합니다.") String content,

        @Schema(description = "원본 문서 URL") @Size(max = 500) @HttpUrl String sourceUrl,

        @Schema(description = "원본 문서 제목") @Size(max = 300) String sourceTitle) {
}
