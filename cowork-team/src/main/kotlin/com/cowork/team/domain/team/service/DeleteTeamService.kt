package com.cowork.team.domain.team.service

interface DeleteTeamService {
    fun execute(userId: Long, teamId: Long)
}
