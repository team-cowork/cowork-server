package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UpdateGithubCommentServiceImpl(
    private val githubCommentAuthorizationSupport: GithubCommentAuthorizationSupport,
    private val githubIssueWriteCommandPublisher: GithubIssueWriteCommandPublisher,
) : UpdateGithubCommentService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        commentId: Long,
        request: UpdateGithubCommentReqDto,
    ) {
        val repo = githubCommentAuthorizationSupport.authorize(userId, projectId, repoId, commentId)
        githubIssueWriteCommandPublisher.publishUpdateComment(
            owner = repo.owner,
            repo = repo.repo,
            repoId = repoId,
            commentId = commentId,
            body = request.body,
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
    }
}
