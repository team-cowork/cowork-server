package com.cowork.team.domain.teamRole.service

interface RevokeTeamRoleService {
    fun revokeRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long)
}
