package com.cowork.project.domain.project.presentation.data.request

import com.fasterxml.jackson.annotation.JsonProperty

import com.cowork.project.domain.project.entity.ProjectStatus
import io.swagger.v3.oas.annotations.media.Schema

data class UpdateProjectReqDto(
    @param:Schema(
        description = "프로젝트 이름. 생략하거나 null이면 기존 이름을 유지합니다. 공백만으로 구성할 수 없고 최대 100자이며, 앞뒤 공백을 포함해 입력값 그대로 저장합니다.",
        example = "코워크 앱 개발",
    )
    @field:ValidProjectName
    val name: String? = null,
    @param:Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String? = null,
    @param:Schema(description = "프로젝트 상태", example = "ARCHIVED")
    val status: ProjectStatus? = null,
    @param:Schema(description = "비공개 여부")
    @field:JsonProperty("isPrivate")
    val isPrivate: Boolean? = null,
)
