package com.cowork.team.domain.team.service

interface DeleteTeamService {
    fun deleteTeam(userId: Long, teamId: Long)
}
