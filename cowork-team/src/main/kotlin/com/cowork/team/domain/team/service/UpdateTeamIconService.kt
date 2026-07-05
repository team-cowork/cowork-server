package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse

interface UpdateTeamIconService {
    fun updateIcon(userId: Long, teamId: Long, iconUrl: String): IconConfirmResponse
}
