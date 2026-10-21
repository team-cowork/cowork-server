package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubIssueStateEvent
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant

/**
 * `cowork-github`가 webhook/backfill로 발행하는 이슈 read-model의 로컬 사본.
 * `GithubAppClient.listIssues`/`getIssue` Feign 호출을 대체한다.
 */
@Entity
@Table(name = "tb_github_issue_projections")
class GithubIssueProjection(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(nullable = false, length = 100)
    val owner: String,

    @Column(nullable = false, length = 100)
    val repo: String,

    @Column(nullable = false)
    val number: Int,

    @Column(nullable = false, length = 500)
    var title: String,

    @Column(nullable = false, length = 100)
    var author: String,

    @Column(nullable = false, length = 20)
    var state: String,

    @Column(name = "html_url", nullable = false, length = 512)
    var htmlUrl: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tb_github_issue_projection_labels", joinColumns = [JoinColumn(name = "issue_id")])
    @Column(name = "label", length = 100)
    var labels: MutableList<String> = mutableListOf(),

    @Column(name = "github_created_at", nullable = false, length = 40)
    var githubCreatedAt: String,

    @Column(name = "github_updated_at", nullable = false, length = 40)
    var githubUpdatedAt: String,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(event: GithubIssueStateEvent): Boolean {
        val version = event.sourceOccurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && deleted && !event.deleted)) {
            return false
        }

        title = event.title
        author = event.author
        state = event.state
        htmlUrl = event.htmlUrl
        labels = event.labels.toMutableList()
        githubCreatedAt = event.createdAt
        githubUpdatedAt = event.updatedAt
        deleted = event.deleted
        sourceOccurredAt = version
        return true
    }

    companion object {
        fun id(owner: String, repo: String, number: Int): String = "$owner/$repo#$number"
    }
}
