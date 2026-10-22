package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubIssueProjection
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import org.springframework.stereotype.Component

@Component
class GithubIssueReader(
    private val repository: GithubIssueProjectionRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {
    fun listIssues(owner: String, repo: String, state: String): List<GithubIssueResDto> {
        val projections = repository.findByOwnerAndRepoOrderByNumberDesc(owner, repo).filter { !it.deleted }
        if (projections.isEmpty()) {
            return callExecutor.execute { githubAppClient.listIssues(owner, repo, state) }
        }

        // GitHub API 응답(생성일 내림차순, per_page=100)과 같은 순서·건수를 유지한다.
        return projections.filter { state == "all" || it.state == state }
            .take(LIST_LIMIT)
            .map { it.toResDto() }
    }

    /** 댓글 부모 작성자 알림(존재하지 않는 이슈면 알림을 생략해도 무방)을 위한 작성자 조회. */
    fun findAuthor(owner: String, repo: String, number: Int): String {
        val projection = repository.findByOwnerAndRepoAndNumber(owner, repo, number)
        if (projection != null && !projection.deleted) {
            return projection.author
        }

        return callExecutor.execute { githubAppClient.getIssue(owner, repo, number) }.author
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
