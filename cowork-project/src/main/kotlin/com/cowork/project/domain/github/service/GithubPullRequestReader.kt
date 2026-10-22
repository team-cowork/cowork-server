package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 그때그때 비동기로 재계산하는 live 상태라
 * 이 reader의 책임이 아니다 — PR 상세 조회는 그 세 필드만 `GithubAppClient`로 직접 조회한다
 * ([com.cowork.project.domain.github.service.impl.QueryPullRequestDetailServiceImpl] 참고).
 */
@Component
class GithubPullRequestReader(
    private val repository: GithubPullRequestProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
) {
    fun listPullRequests(owner: String, repo: String, state: String): List<GithubPullRequestSummaryResDto> {
        syncMarkers.requireSynced(GithubSyncStream.PULL_REQUEST, owner, repo)
        // GitHub API 응답(생성일 내림차순, per_page=100)과 같은 순서·건수를 유지한다.
        return repository.findByOwnerAndRepoOrderByNumberDesc(owner, repo)
            .filter { state == "all" || it.state == state }
            .take(LIST_LIMIT)
            .map { it.toSummaryResDto() }
    }

    fun listFiles(owner: String, repo: String, prNumber: Int): List<GithubPullRequestFileResDto> =
        findProjection(owner, repo, prNumber).files.map {
            GithubPullRequestFileResDto(
                filename = it.filename,
                status = it.status,
                additions = it.additions,
                deletions = it.deletions,
                patch = it.patch,
            )
        }

    /** 댓글 부모 작성자 알림을 위한 작성자 조회. 찾지 못하면 예외를 던지고, 호출자는 알림을 생략한다. */
    fun findAuthor(owner: String, repo: String, number: Int): String = findProjection(owner, repo, number).author

    private fun findProjection(owner: String, repo: String, number: Int): GithubPullRequestProjection {
        syncMarkers.requireSynced(GithubSyncStream.PULL_REQUEST, owner, repo)
        return repository.findByOwnerAndRepoAndNumber(owner, repo, number)
            ?: throw ExpectedException("PR을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    private fun GithubPullRequestProjection.toSummaryResDto() = GithubPullRequestSummaryResDto(
        number = number,
        title = title,
        author = author,
        state = state,
        draft = draft,
        merged = merged,
        htmlUrl = htmlUrl,
        labels = labels,
        createdAt = githubCreatedAt,
        updatedAt = githubUpdatedAt,
    )

    private companion object {
        const val LIST_LIMIT = 100
    }
}
