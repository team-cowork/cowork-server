package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamMember.presentation.data.request.ChangeNicknameRequest

interface ChangeTeamMemberNicknameService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeNicknameRequest)
}
