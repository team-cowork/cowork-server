package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.GithubInstallUrlResponse

interface GenerateGithubInstallUrlService {
    fun execute(userId: Long, teamId: Long): GithubInstallUrlResponse
}
