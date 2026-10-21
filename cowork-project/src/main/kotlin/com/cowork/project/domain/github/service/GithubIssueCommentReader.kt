package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubIssueCommentProjection
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.repository.GithubIssueCommentProjectionRepository
import org.springframework.stereotype.Component

@Component
class GithubIssueCommentReader(
    private val repository: GithubIssueCommentProjectionRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {
    fun listComments(owner: String, repo: String, number: Int): List<GithubCommentResDto> {
        val projections = repository.findByOwnerAndRepoAndParentNumberAndDeletedFalse(owner, repo, number)
        if (projections.isEmpty()) {
            return callExecutor.execute { githubAppClient.listIssueComments(owner, repo, number) }
        }

        return projections.map { it.toResDto() }
    }

    /** 댓글 작성자 확인(수정/삭제 권한 검사)을 위한 단건 조회. */
    fun findComment(owner: String, repo: String, commentId: Long): GithubCommentResDto {
        val projection = repository.findByOwnerAndRepoAndCommentId(owner, repo, commentId)
        if (projection != null && !projection.deleted) {
            return projection.toResDto()
        }

        return callExecutor.execute { githubAppClient.getIssueComment(owner, repo, commentId) }
    }

    private fun GithubIssueCommentProjection.toResDto() = GithubCommentResDto(
        id = commentId,
        author = author,
        body = body,
        htmlUrl = htmlUrl,
        createdAt = githubCreatedAt,
        updatedAt = githubUpdatedAt,
    )
}
