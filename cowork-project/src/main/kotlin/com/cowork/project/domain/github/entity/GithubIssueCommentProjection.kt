package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubIssueCommentStateEvent
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

/**
 * `cowork-github`가 webhook/backfill로 발행하는 이슈/PR 댓글 read-model의 로컬 사본.
 * `GithubAppClient.listIssueComments`/`getIssueComment` Feign 호출을 대체한다.
 *
 * `author`는 cowork-github가 댓글 본문 끝의 숨은 마커에서 추출한 실제 작성자다(GitHub App
 * 설치 토큰으로 작성하면 GitHub상 작성자는 항상 봇 계정으로 보이기 때문).
 */
@Entity
@Table(name = "tb_github_issue_comment_projections")
class GithubIssueCommentProjection(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(nullable = false, length = 100)
    val owner: String,

    @Column(nullable = false, length = 100)
    val repo: String,

    @Column(name = "comment_id", nullable = false)
    val commentId: Long,

    @Column(name = "parent_type", nullable = false, length = 20)
    var parentType: String,

    @Column(name = "parent_number", nullable = false)
    var parentNumber: Int,

    @Column(nullable = false, length = 100)
    var author: String,

    @Lob
    @Column(nullable = false)
    var body: String,

    @Column(name = "html_url", nullable = false, length = 512)
    var htmlUrl: String,

    @Column(name = "github_created_at", nullable = false, length = 40)
    var githubCreatedAt: String,

    @Column(name = "github_updated_at", nullable = false, length = 40)
    var githubUpdatedAt: String,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(event: GithubIssueCommentStateEvent): Boolean {
        val version = event.sourceOccurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && deleted && !event.deleted)) {
            return false
        }

        parentType = event.parentType
        parentNumber = event.parentNumber
        author = event.author
        body = event.body
        htmlUrl = event.htmlUrl
        githubCreatedAt = event.createdAt
        githubUpdatedAt = event.updatedAt
        deleted = event.deleted
        sourceOccurredAt = version
        return true
    }

    companion object {
        fun id(owner: String, repo: String, commentId: Long): String = "$owner/$repo#$commentId"
    }
}
