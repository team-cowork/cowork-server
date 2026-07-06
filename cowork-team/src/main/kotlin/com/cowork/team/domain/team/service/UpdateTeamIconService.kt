package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse

interface UpdateTeamIconService {
    fun execute(userId: Long, teamId: Long, iconUrl: String): IconConfirmResponse
}
