package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubPullRequestActionRequest
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestActionResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.github.service.MergePullRequestService
import org.springframework.stereotype.Service

@Service
class MergePullRequestServiceImpl(
    private val githubRepoAccessResolver: GithubRepoAccessResolver,
    private val githubUsernameResolver: GithubUsernameResolver,
    private val githubAppCallExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : MergePullRequestService {

    // GitHub 응답(read-timeout 10초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestActionResDto {
        val repo = githubRepoAccessResolver.resolveForModify(userId, projectId, repoId)
        val request = GithubPullRequestActionRequest(githubUsernameResolver.resolve(userId))
        val result = githubAppCallExecutor.execute {
            githubAppClient.mergePullRequest(repo.owner, repo.repo, prNumber, request)
        }
        return GithubPullRequestActionResDto.of(result)
    }
}
