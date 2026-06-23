package com.cowork.team.domain.teamMember.presentation.data.request

import com.cowork.team.domain.teamRole.entity.TeamRole
import io.swagger.v3.oas.annotations.media.Schema

data class ChangeRoleRequest(
    @param:Schema(description = "변경할 역할", example = "ADMIN", allowableValues = ["ADMIN", "MEMBER"], required = true)
    val role: TeamRole,
)
