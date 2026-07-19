package com.cowork.team.domain.teamRole.service

interface RevokeTeamRoleService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long)
}
