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
 * 이 reader의 책임이 아니다 — PR 상세 조회는 나머지 필드를 [findPullRequest]로 채우고, 그 세 필드만
 * `GithubAppClient`로 직접 조회한다
 * ([com.cowork.project.domain.github.service.impl.QueryPullRequestDetailServiceImpl] 참고).
 */
@Component
class GithubPullRequestReader(
    private val repository: GithubPullRequestProjectionRepository,
    private val syncMarkers: GithubSyncMarkers,
) {
    fun listPullRequests(owner: String, repo: String, state: String): List<GithubPullRequestSummaryResDto> {
        syncMarkers.requireSynced(GithubSyncStream.PULL_REQUEST, owner, repo)
        // GitHub API 응답(생성일 내림차순, per_page=100)과 같은 순서·건수를 DB 조회에서 유지한다.
        val pullRequests = if (state == "all") {
            repository.findTop100ByOwnerAndRepoOrderByNumberDesc(owner, repo)
        } else {
            repository.findTop100ByOwnerAndRepoAndStateOrderByNumberDesc(owner, repo, state)
        }
        return pullRequests.map { it.toSummaryResDto() }
    }

    fun listFiles(owner: String, repo: String, prNumber: Int): List<GithubPullRequestFileResDto> =
        findPullRequest(owner, repo, prNumber).files.map {
            GithubPullRequestFileResDto(
                filename = it.filename,
                status = it.status,
                additions = it.additions,
                deletions = it.deletions,
                patch = it.patch,
            )
        }

    /** 댓글 부모 작성자 알림을 위한 작성자 조회. 찾지 못하면 예외를 던지고, 호출자는 알림을 생략한다. */
    fun findAuthor(owner: String, repo: String, number: Int): String = findPullRequest(owner, repo, number).author

    /** 동기화가 끝나지 않았으면 503, PR이 없으면 404를 던진다. */
    fun findPullRequest(owner: String, repo: String, number: Int): GithubPullRequestProjection {
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
}
