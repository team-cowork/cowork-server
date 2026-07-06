package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.service.ApprovePullRequestService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ApprovePullRequestServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : ApprovePullRequestService {

    override fun execute(userId: Long, projectId: Long, prNumber: Int): GithubApproveResultResDto {
        val repo = repoAccessResolver.resolveForModify(userId, projectId)
        val githubUsername = usernameResolver.resolve(userId)
        return callExecutor.execute {
            githubAppClient.approvePullRequest(
                repo.owner,
                repo.repo,
                prNumber,
                mapOf(
                    "requesterGithubUsername" to githubUsername,
                ),
            )
        }
    }
}
