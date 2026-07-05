package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.team.presentation.data.response.JoinTeamResponse

interface JoinTeamService {
    fun joinTeam(userId: Long, inviteCode: String): JoinTeamResponse
}
