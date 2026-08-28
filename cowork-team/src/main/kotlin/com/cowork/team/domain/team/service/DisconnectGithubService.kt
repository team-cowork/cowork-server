package com.cowork.team.domain.team.service

interface DisconnectGithubService {
    fun execute(userId: Long, teamId: Long)
}
