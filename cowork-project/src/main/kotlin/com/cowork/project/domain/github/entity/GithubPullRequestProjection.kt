package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubPullRequestStateEvent
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

@Embeddable
class GithubPullRequestProjectionFile(
    @Column(nullable = false, length = 1024)
    val filename: String = "",

    @Column(nullable = false, length = 20)
    val status: String = "",

    @Column(nullable = false)
    val additions: Int = 0,

    @Column(nullable = false)
    val deletions: Int = 0,

    @Lob
    @Column
    val patch: String? = null,
)

/**
 * `cowork-github`가 webhook/backfill로 발행하는 PR read-model의 로컬 사본.
 * `GithubAppClient.listPullRequests`/`listPullRequestFiles` Feign 호출을 대체한다.
 *
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 그때그때 비동기로 재계산하는
 * 값이라(웹훅/이벤트로 재구성할 수 없는 live 상태) 이 projection에 포함하지 않는다 — PR 상세
 * 조회는 그 세 필드만 `GithubAppClient`로 직접 조회한다.
 */
@Entity
@Table(name = "tb_github_pull_request_projections")
class GithubPullRequestProjection(
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

    @Lob
    @Column
    var body: String? = null,

    @Column(nullable = false, length = 100)
    var author: String,

    @Column(nullable = false, length = 20)
    var state: String,

    @Column(nullable = false)
    var draft: Boolean,

    @Column(nullable = false)
    var merged: Boolean,

    @Column(name = "html_url", nullable = false, length = 512)
    var htmlUrl: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tb_github_pr_projection_labels", joinColumns = [JoinColumn(name = "pr_id")])
    @Column(name = "label", length = 100)
    var labels: MutableList<String> = mutableListOf(),

    @Column(name = "head_ref", nullable = false, length = 300)
    var headRef: String,

    @Column(name = "base_ref", nullable = false, length = 300)
    var baseRef: String,

    @Column(name = "github_created_at", nullable = false, length = 40)
    var githubCreatedAt: String,

    @Column(name = "github_updated_at", nullable = false, length = 40)
    var githubUpdatedAt: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tb_github_pr_projection_files", joinColumns = [JoinColumn(name = "pr_id")])
    var files: MutableList<GithubPullRequestProjectionFile> = mutableListOf(),

    @Column(name = "files_source_occurred_at", nullable = false)
    var filesSourceOccurredAt: Instant = Instant.EPOCH,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(event: GithubPullRequestStateEvent): Boolean {
        val version = event.sourceOccurredAt.toProjectionPrecision()
        val filesApplied = applyFiles(event, version)
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version)) {
            return filesApplied
        }

        title = event.title
        body = event.body
        author = event.author
        state = event.state
        draft = event.draft
        merged = event.merged
        htmlUrl = event.htmlUrl
        labels = event.labels.toMutableList()
        headRef = event.headRef
        baseRef = event.baseRef
        githubCreatedAt = event.createdAt
        githubUpdatedAt = event.updatedAt
        sourceOccurredAt = version
        return true
    }

    // files는 opened/synchronize/reopened에만 실리므로 별도 버전으로 관리한다 — 늦게 도착한 synchronize가
    // 그 사이 먼저 적용된 labeled 같은 이벤트에 밀려 파일 갱신까지 버려지지 않게 한다.
    private fun applyFiles(event: GithubPullRequestStateEvent, version: Instant): Boolean {
        val newFiles = event.files ?: return false
        if (filesSourceOccurredAt.toProjectionPrecision().isAfter(version)) {
            return false
        }

        files = newFiles.map {
            GithubPullRequestProjectionFile(it.filename, it.status, it.additions, it.deletions, it.patch)
        }.toMutableList()
        filesSourceOccurredAt = version
        return true
    }

    companion object {
        fun id(owner: String, repo: String, number: Int): String = "$owner/$repo#$number"
    }
}
