package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse

interface QueryInvitesService {
    fun execute(userId: Long, teamId: Long): List<InviteResponse>
}
