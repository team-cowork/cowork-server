package com.cowork.team.domain.teamRole

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface TeamRoleQueries {
    fun getRoles(teamId: Long): List<TeamRoleResponse>
    fun getMemberRoles(teamId: Long, accountId: Long): List<TeamRoleResponse>
    fun getMemberRoleAssignments(teamId: Long): List<TeamMemberRoleAssignmentResponse>
}
