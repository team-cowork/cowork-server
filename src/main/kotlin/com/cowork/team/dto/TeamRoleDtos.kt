package com.cowork.team.dto

import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamRole

data class CreateTeamRoleRequest(
    val name: String,
    val colorHex: String? = null,
    val priority: Int = 0,
    val mentionable: Boolean = false,
    val permissions: Set<TeamPermission> = emptySet(),
)

data class UpdateTeamRoleRequest(
    val name: String? = null,
    val colorHex: String? = null,
    val priority: Int? = null,
    val mentionable: Boolean? = null,
    val permissions: Set<TeamPermission>? = null,
)

data class TeamRoleResponse(
    val id: Long,
    val teamId: Long,
    val name: String,
    val colorHex: String,
    val priority: Int,
    val mentionable: Boolean,
    val permissions: Set<TeamPermission>,
) {
    companion object {
        fun from(role: TeamRole) = TeamRoleResponse(
            id = role.id,
            teamId = role.teamId,
            name = role.name,
            colorHex = role.colorHex,
            priority = role.priority,
            mentionable = role.mentionable,
            permissions = role.permissions,
        )
    }
}

private const val HEX_COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$"
