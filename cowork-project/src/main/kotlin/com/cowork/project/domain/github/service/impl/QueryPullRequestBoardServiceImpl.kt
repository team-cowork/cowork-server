package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryPullRequestBoardService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryPullRequestBoardServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : QueryPullRequestBoardService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): GithubPullRequestBoardResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId)
        val pulls = callExecutor.execute { githubAppClient.listPullRequests(repo.owner, repo.repo, "open") }
        val (draft, inReview) = pulls.partition { it.draft }
        return GithubPullRequestBoardResDto(draft = draft, inReview = inReview)
    }
}
