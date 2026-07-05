package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest

interface ChangeTeamMemberRoleService {
    fun changeRole(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeRoleRequest)
}
