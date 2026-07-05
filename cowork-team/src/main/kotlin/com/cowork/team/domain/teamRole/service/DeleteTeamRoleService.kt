package com.cowork.team.domain.teamRole.service

interface DeleteTeamRoleService {
    fun deleteRole(actorId: Long, teamId: Long, roleId: Long)
}
