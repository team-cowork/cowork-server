package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GetPullRequestDetailService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetPullRequestDetailServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : GetPullRequestDetailService {

    override fun getPullRequestDetail(userId: Long, projectId: Long, prNumber: Int): GithubPullRequestResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId)
        return callExecutor.execute { githubAppClient.getPullRequest(repo.owner, repo.repo, prNumber) }
    }
}
