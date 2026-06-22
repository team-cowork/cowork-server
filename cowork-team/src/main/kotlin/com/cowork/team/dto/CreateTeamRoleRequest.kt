package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

data class CreateTeamRoleRequest(
    @param:Schema(description = "역할 이름", example = "Frontend")
    val name: String,
    @param:Schema(description = "역할 색상 HEX", example = "#5865F2")
    val colorHex: String = "#99AAB5",
    @param:Schema(description = "역할 우선순위. 숫자가 높을수록 상위 역할", example = "10")
    val priority: Int = 0,
    @param:Schema(description = "역할 멘션 허용 여부", example = "true")
    val mentionable: Boolean = false,
    @param:Schema(description = "권한 목록")
    val permissions: Set<String> = emptySet(),
)
