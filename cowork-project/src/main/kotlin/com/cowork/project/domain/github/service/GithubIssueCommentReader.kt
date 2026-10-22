package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubIssueCommentProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.repository.GithubIssueCommentProjectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class GithubIssueCommentReader(
    private val repository: GithubIssueCommentProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
) {
    fun listComments(owner: String, repo: String, number: Int): List<GithubCommentResDto> {
        syncMarkers.requireSynced(GithubSyncStream.ISSUE_COMMENT, owner, repo)
        val projections = repository.findByOwnerAndRepoAndParentNumberAndDeletedFalseOrderByGithubCreatedAtAsc(
            owner,
            repo,
            number,
        )
        // GitHub API 응답(생성일 오름차순, per_page=100)과 같은 순서·건수를 유지한다.
        return projections.take(LIST_LIMIT).map { it.toResDto() }
    }

    /** 댓글 작성자 확인(수정/삭제 권한 검사)을 위한 단건 조회. */
    fun findComment(owner: String, repo: String, commentId: Long): GithubCommentResDto {
        syncMarkers.requireSynced(GithubSyncStream.ISSUE_COMMENT, owner, repo)
        val projection = repository.findByOwnerAndRepoAndCommentId(owner, repo, commentId)?.takeUnless { it.deleted }
            ?: throw ExpectedException("댓글을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return projection.toResDto()
    }

    private fun GithubIssueCommentProjection.toResDto() = GithubCommentResDto(
        id = commentId,
        author = author,
        body = body,
        htmlUrl = htmlUrl,
        createdAt = githubCreatedAt,
        updatedAt = githubUpdatedAt,
    )

    private companion object {
        const val LIST_LIMIT = 100
    }
}
