package com.cowork.roadmap.domain.roadmap.presentation.data.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** PATCH 의미: null인 필드는 변경하지 않는다. */
public record UpdateRoadmapReqDto(

        @Schema(description = "로드맵 제목") @Size(max = 150) String title,

        @Schema(description = "로드맵 설명") @Size(max = 1000) String description,

        @Schema(description = "종류/전공/포지션") @Size(max = 50) String category) {
}
