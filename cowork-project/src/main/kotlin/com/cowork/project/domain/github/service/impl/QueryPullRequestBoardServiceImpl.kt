package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto
import com.cowork.project.domain.github.service.GithubPullRequestReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryPullRequestBoardService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryPullRequestBoardServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val pullRequestReader: GithubPullRequestReader,
) : QueryPullRequestBoardService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long): GithubPullRequestBoardResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val pulls = pullRequestReader.listPullRequests(repo.owner, repo.repo, "open")
        val (draft, inReview) = pulls.partition { it.draft }
        return GithubPullRequestBoardResDto(draft = draft, inReview = inReview)
    }
}
