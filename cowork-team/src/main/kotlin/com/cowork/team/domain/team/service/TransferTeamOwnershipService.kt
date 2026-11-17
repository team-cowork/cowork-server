package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface TransferTeamOwnershipService {
    fun execute(actorId: Long, teamId: Long, targetUserId: Long): TeamResponse
}
