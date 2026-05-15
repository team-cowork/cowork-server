package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateProjectRequest(
    @Schema(description = "프로젝트 이름", example = "코워크 앱 개발")
    val name: String? = null,
    @Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String? = null,
    @Schema(description = "프로젝트 상태", example = "ARCHIVED", allowableValues = ["ACTIVE", "ARCHIVED"])
    val status: String? = null,
)
