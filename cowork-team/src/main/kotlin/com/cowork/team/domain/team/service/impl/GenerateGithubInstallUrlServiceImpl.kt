package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.GithubInstallUrlResponse
import com.cowork.team.domain.team.service.GenerateGithubInstallUrlService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import com.cowork.team.global.config.TeamGithubProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GenerateGithubInstallUrlServiceImpl(
    private val teamAccessGuard: TeamAccessGuard,
    private val teamGithubStateSupport: TeamGithubStateSupport,
    private val teamGithubProperties: TeamGithubProperties,
) : GenerateGithubInstallUrlService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): GithubInstallUrlResponse {
        teamAccessGuard.requireMemberExists(teamId, userId)
        val state = teamGithubStateSupport.buildState(teamId, userId)
        val installUrl = "https://github.com/apps/${teamGithubProperties.appSlug}/installations/new?state=$state"
        return GithubInstallUrlResponse(installUrl = installUrl)
    }
}
