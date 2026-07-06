package com.cowork.team.domain.teamRole.service

interface DeleteTeamRoleService {
    fun execute(actorId: Long, teamId: Long, roleId: Long)
}
