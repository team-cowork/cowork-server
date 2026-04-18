package com.cowork.team.dto

import com.cowork.team.domain.TeamRole

data class ChangeRoleRequest(
    val role: TeamRole,
)
