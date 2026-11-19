package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.teamMember.JoinTeamResponse

interface JoinTeamService {
    fun execute(userId: Long, inviteCode: String): JoinTeamResponse
}
