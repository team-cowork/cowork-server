package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateTeamRoleRequest(
    @param:Schema(description = "역할 이름", example = "Backend")
    val name: String? = null,
    @param:Schema(description = "역할 색상 HEX", example = "#57F287")
    val colorHex: String? = null,
    @param:Schema(description = "역할 우선순위. 숫자가 높을수록 상위 역할", example = "20")
    val priority: Int? = null,
    @param:Schema(description = "역할 멘션 허용 여부", example = "false")
    val mentionable: Boolean? = null,
    @param:Schema(description = "권한 목록")
    val permissions: Set<String>? = null,
)
