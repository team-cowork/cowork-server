package com.cowork.roadmap.domain.node.presentation.data.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NodeReferenceReqDto(

        @Schema(description = "관련 자료 제목") @NotBlank @Size(max = 200) String title,

        @Schema(description = "관련 자료 링크") @NotBlank @Size(max = 500) String url) {
}
