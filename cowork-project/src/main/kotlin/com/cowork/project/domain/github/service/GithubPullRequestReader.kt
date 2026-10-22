package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import org.springframework.stereotype.Component

/**
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 그때그때 비동기로 재계산하는 live 상태라
 * 이 reader의 책임이 아니다 — PR 상세 조회는 그 세 필드만 `GithubAppClient`로 직접 조회한다
 * ([com.cowork.project.domain.github.service.impl.QueryPullRequestDetailServiceImpl] 참고).
 */
@Component
class GithubPullRequestReader(
    private val repository: GithubPullRequestProjectionRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {
    fun listPullRequests(owner: String, repo: String, state: String): List<GithubPullRequestSummaryResDto> {
        val projections = repository.findByOwnerAndRepoOrderByNumberDesc(owner, repo)
        if (projections.isEmpty()) {
            return callExecutor.execute { githubAppClient.listPullRequests(owner, repo, state) }
        }

        // GitHub API 응답(생성일 내림차순, per_page=100)과 같은 순서·건수를 유지한다.
        return projections.filter { state == "all" || it.state == state }
            .take(LIST_LIMIT)
            .map { it.toSummaryResDto() }
    }

    fun listFiles(owner: String, repo: String, prNumber: Int): List<GithubPullRequestFileResDto> {
        val projection = repository.findByOwnerAndRepoAndNumber(owner, repo, prNumber)
        if (projection == null || projection.files.isEmpty()) {
            return callExecutor.execute { githubAppClient.listPullRequestFiles(owner, repo, prNumber) }
        }

        return projection.files.map {
            GithubPullRequestFileResDto(
                filename = it.filename,
                status = it.status,
                additions = it.additions,
                deletions = it.deletions,
                patch = it.patch,
            )
        }
    }

    /** 댓글 부모 작성자 알림을 위한 작성자 조회. */
    fun findAuthor(owner: String, repo: String, number: Int): String {
        val projection = repository.findByOwnerAndRepoAndNumber(owner, repo, number)
        if (projection != null) {
            return projection.author
        }

        return callExecutor.execute { githubAppClient.getPullRequest(owner, repo, number) }.author
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
