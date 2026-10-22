package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.GithubIssueCommentReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.ListGithubCommentsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListGithubCommentsServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val commentReader: GithubIssueCommentReader,
) : ListGithubCommentsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, number: Int): List<GithubCommentResDto> {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        return commentReader.listComments(repo.owner, repo.repo, number)
    }
}
