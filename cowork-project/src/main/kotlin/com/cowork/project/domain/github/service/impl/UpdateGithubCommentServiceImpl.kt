package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppUpdateCommentReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentAuthorizationSupport
import com.cowork.project.domain.github.service.UpdateGithubCommentService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateGithubCommentServiceImpl(
    private val authorizationSupport: GithubCommentAuthorizationSupport,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : UpdateGithubCommentService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        commentId: Long,
        request: UpdateGithubCommentReqDto,
    ): GithubCommentResDto {
        val repo = authorizationSupport.authorize(userId, projectId, repoId, commentId)
        return callExecutor.execute {
            githubAppClient.updateIssueComment(repo.owner, repo.repo, commentId, GithubAppUpdateCommentReqDto(request.body))
        }
    }
}
