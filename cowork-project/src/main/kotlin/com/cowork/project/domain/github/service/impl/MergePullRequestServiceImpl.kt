package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.github.service.MergePullRequestService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MergePullRequestServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : MergePullRequestService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, prNumber: Int): GithubMergeResultResDto {
        val repo = repoAccessResolver.resolveForModify(userId, projectId)
        val githubUsername = usernameResolver.resolve(userId)
        return callExecutor.execute {
            githubAppClient.mergePullRequest(
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
