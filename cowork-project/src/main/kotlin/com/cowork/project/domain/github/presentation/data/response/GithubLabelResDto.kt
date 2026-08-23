package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "레포에 정의된 GitHub 라벨")
data class GithubLabelResDto(
    @field:Schema(description = "라벨 이름", example = "bug")
    val name: String,
    @field:Schema(description = "라벨 색상 (hex, # 제외)", example = "d73a4a")
    val color: String,
)
