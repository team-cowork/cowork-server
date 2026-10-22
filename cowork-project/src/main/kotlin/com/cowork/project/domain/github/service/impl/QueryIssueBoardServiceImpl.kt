package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.GithubIssueReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryIssueBoardService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryIssueBoardServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val issueReader: GithubIssueReader,
) : QueryIssueBoardService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long): List<GithubIssueResDto> {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        return issueReader.listIssues(repo.owner, repo.repo, "open")
    }
}
