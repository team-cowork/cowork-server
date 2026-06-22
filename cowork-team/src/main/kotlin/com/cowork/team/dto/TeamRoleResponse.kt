package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

data class TeamRoleResponse(
    @field:Schema(description = "역할 ID", example = "1")
    val id: Long,
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "역할 이름", example = "Frontend")
    val name: String,
    @field:Schema(description = "역할 색상 HEX", example = "#5865F2")
    val colorHex: String,
    @field:Schema(description = "역할 우선순위. 숫자가 높을수록 상위 역할", example = "10")
    val priority: Int,
    @field:Schema(description = "역할 멘션 허용 여부", example = "true")
    val mentionable: Boolean,
    @field:Schema(description = "권한 목록")
    val permissions: Set<String>,
    @field:Schema(description = "생성 일시")
    val createdAt: String?,
    @field:Schema(description = "수정 일시")
    val updatedAt: String?,
)
