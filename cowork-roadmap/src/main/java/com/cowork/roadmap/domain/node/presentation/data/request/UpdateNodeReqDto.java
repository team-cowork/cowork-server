package com.cowork.roadmap.domain.node.presentation.data.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** PATCH 의미: null인 필드는 변경하지 않는다. */
public record UpdateNodeReqDto(

        @Schema(description = "노드/문서 제목") @Size(max = 200) String title,

        @Schema(description = "번역된 본문 (마크다운)") String content,

        @Schema(description = "원본 문서 URL") @Size(max = 500) String sourceUrl,

        @Schema(description = "원본 문서 제목") @Size(max = 300) String sourceTitle) {
}
