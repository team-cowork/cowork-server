package com.cowork.project.domain.project.presentation.data.request

import com.fasterxml.jackson.annotation.JsonProperty

import com.cowork.project.domain.project.entity.ProjectStatus
import io.swagger.v3.oas.annotations.media.Schema

data class UpdateProjectReqDto(
    @param:Schema(description = "프로젝트 이름", example = "코워크 앱 개발")
    val name: String? = null,
    @param:Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String? = null,
    @param:Schema(description = "프로젝트 상태", example = "ARCHIVED")
    val status: ProjectStatus? = null,
    @param:Schema(description = "비공개 여부")
    @field:JsonProperty("isPrivate")
    val isPrivate: Boolean? = null,
)
