package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.teamInvite.presentation.data.request.CreateInviteRequest
import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse

interface CreateInviteService {
    fun execute(userId: Long, teamId: Long, request: CreateInviteRequest): InviteResponse
}
