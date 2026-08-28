package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.service.DeleteGithubCommentService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeleteGithubCommentServiceImpl(
    private val authorizationSupport: GithubCommentAuthorizationSupport,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : DeleteGithubCommentService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, commentId: Long) {
        val repo = authorizationSupport.authorize(userId, projectId, repoId, commentId)
        callExecutor.execute { githubAppClient.deleteIssueComment(repo.owner, repo.repo, commentId) }
    }
}
