package com.cowork.project.domain.project.presentation.data.request

import com.fasterxml.jackson.annotation.JsonProperty

import io.swagger.v3.oas.annotations.media.Schema

data class CreateProjectReqDto(
    @param:Schema(description = "팀 ID", example = "1", required = true)
    val teamId: Long,
    @param:Schema(description = "프로젝트 이름", example = "코워크 앱 개발", required = true)
    val name: String,
    @param:Schema(description = "프로젝트 설명", example = "모바일 앱 개발 프로젝트")
    val description: String? = null,
    @param:Schema(description = "비공개 여부. 비공개 프로젝트는 프로젝트 역할의 명시적 읽기 허용이 필요합니다.")
    @field:JsonProperty("isPrivate")
    val isPrivate: Boolean = false,
)
