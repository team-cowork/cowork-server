package com.cowork.team.support

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import io.mockk.every
import io.mockk.mockk

fun policyRole(permissions: Map<String, Boolean>, priority: Int = 10, teamId: Long = 1) = TeamRoleResponse(
    id = 100, teamId = teamId, name = "themoment-admin", colorHex = "#000000", priority = priority,
    mentionable = false, permissions = permissions, createdAt = null, updatedAt = null, isDefault = false,
)

fun policyReader(teamId: Long, grants: Map<Long, Map<String, Boolean>>): TeamRoleProjectionReader =
    mockk<TeamRoleProjectionReader>().also { reader ->
        every { reader.getMemberRoles(any(), any()) } answers {
            if (firstArg<Long>() ==
                teamId
            ) {
                grants[secondArg<Long>()]?.let { listOf(policyRole(it, teamId = teamId)) }.orEmpty()
            } else {
                emptyList()
            }
        }
    }
