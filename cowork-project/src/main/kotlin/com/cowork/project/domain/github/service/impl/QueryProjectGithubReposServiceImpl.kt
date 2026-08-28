package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.QueryProjectGithubReposService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryProjectGithubReposServiceImpl(
    private val projectAccessGuard: ProjectAccessGuard,
    private val teamGithubInstallationRepository: TeamGithubInstallationRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) : QueryProjectGithubReposService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<GithubRepoSummaryResDto> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)

        val installation = teamGithubInstallationRepository.findById(project.teamId).orElseThrow {
            ExpectedException("팀이 GitHub 조직에 연결되어 있지 않습니다.", HttpStatus.BAD_REQUEST)
        }

        return callExecutor.execute { githubAppClient.listOrgRepos(installation.orgLogin) }
    }
}
