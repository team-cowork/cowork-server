package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse

interface GetInvitesService {
    fun getInvites(userId: Long, teamId: Long): List<InviteResponse>
}
