package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeleteGithubCommentServiceImpl(
    private val authorizationSupport: GithubCommentAuthorizationSupport,
    private val commandPublisher: GithubIssueWriteCommandPublisher,
) : DeleteGithubCommentService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, repoId: Long, commentId: Long) {
        val repo = authorizationSupport.authorize(userId, projectId, repoId, commentId)
        commandPublisher.publishDeleteComment(
            owner = repo.owner,
            repo = repo.repo,
            repoId = repoId,
            commentId = commentId,
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
    }
}
