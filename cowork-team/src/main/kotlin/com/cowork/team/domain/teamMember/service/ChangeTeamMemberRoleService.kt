package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest

interface ChangeTeamMemberRoleService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeRoleRequest)
}
