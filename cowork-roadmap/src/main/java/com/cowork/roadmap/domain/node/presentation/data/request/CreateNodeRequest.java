package com.cowork.roadmap.domain.node.presentation.data.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNodeRequest(

        @Schema(description = "상위 노드 ID (루트면 null)") Long parentId,

        @Schema(description = "노드/문서 제목", example = "Dart로 AI 만들기") @NotBlank @Size(max = 200) String title,

        @Schema(description = "번역된 본문 (마크다운)") String content,

        @Schema(description = "원본 문서 URL") @Size(max = 500) String sourceUrl,

        @Schema(description = "원본 문서 제목") @Size(max = 300) String sourceTitle) {
}
