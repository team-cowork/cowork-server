package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeleteGithubCommentServiceImpl(
    private val githubCommentAuthorizationSupport: GithubCommentAuthorizationSupport,
    private val githubIssueWriteCommandPublisher: GithubIssueWriteCommandPublisher,
) : DeleteGithubCommentService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, repoId: Long, commentId: Long) {
        val repo = githubCommentAuthorizationSupport.authorize(userId, projectId, repoId, commentId)
        githubIssueWriteCommandPublisher.publishDeleteComment(
            owner = repo.owner,
            repo = repo.repo,
            repoId = repoId,
            commentId = commentId,
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
    }
}
