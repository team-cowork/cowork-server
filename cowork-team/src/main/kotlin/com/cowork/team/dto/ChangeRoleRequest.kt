package com.cowork.team.dto

import com.cowork.team.domain.TeamRole
import io.swagger.v3.oas.annotations.media.Schema

data class ChangeRoleRequest(
    @Schema(description = "변경할 역할", example = "ADMIN", allowableValues = ["ADMIN", "MEMBER"], required = true)
    val role: TeamRole,
)
