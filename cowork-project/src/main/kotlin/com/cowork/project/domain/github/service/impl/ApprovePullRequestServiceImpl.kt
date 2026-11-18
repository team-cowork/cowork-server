package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubPullRequestActionRequest
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestActionResDto
import com.cowork.project.domain.github.service.ApprovePullRequestService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import org.springframework.stereotype.Service

@Service
class ApprovePullRequestServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : ApprovePullRequestService {

    // GitHub 응답(read-timeout 10초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestActionResDto {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        val request = GithubPullRequestActionRequest(usernameResolver.resolve(userId))
        val result = callExecutor.execute {
            githubAppClient.approvePullRequest(repo.owner, repo.repo, prNumber, request)
        }
        return GithubPullRequestActionResDto.of(result)
    }
}
