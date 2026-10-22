package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubIssueProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class GithubIssueReader(
    private val repository: GithubIssueProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
) {
    fun listIssues(owner: String, repo: String, state: String): List<GithubIssueResDto> {
        syncMarkers.requireSynced(GithubSyncStream.ISSUE, owner, repo)
        // GitHub API 응답(생성일 내림차순, per_page=100)과 같은 순서·건수를 유지한다.
        return repository.findByOwnerAndRepoOrderByNumberDesc(owner, repo)
            .filter { !it.deleted && (state == "all" || it.state == state) }
            .take(LIST_LIMIT)
            .map { it.toResDto() }
    }

    /** 댓글 부모 작성자 알림을 위한 작성자 조회. 찾지 못하면 예외를 던지고, 호출자는 알림을 생략한다. */
    fun findAuthor(owner: String, repo: String, number: Int): String {
        syncMarkers.requireSynced(GithubSyncStream.ISSUE, owner, repo)
        val projection = repository.findByOwnerAndRepoAndNumber(owner, repo, number)?.takeUnless { it.deleted }
            ?: throw ExpectedException("이슈를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return projection.author
    }

    private fun GithubIssueProjection.toResDto() = GithubIssueResDto(
        number = number,
        title = title,
        author = author,
        state = state,
        htmlUrl = htmlUrl,
        labels = labels,
        createdAt = githubCreatedAt,
        updatedAt = githubUpdatedAt,
    )

    private companion object {
        const val LIST_LIMIT = 100
    }
}
