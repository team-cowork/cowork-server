package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

data class CreateProjectRequest(
    @Schema(description = "팀 ID", example = "1", required = true)
    val teamId: Long,
    @Schema(description = "프로젝트 이름", example = "코워크 앱 개발", required = true)
    val name: String,
    @Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String? = null,
)
