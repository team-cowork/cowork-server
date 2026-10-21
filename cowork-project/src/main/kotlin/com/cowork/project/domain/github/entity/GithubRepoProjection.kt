package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubRepoStateEvent
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `cowork-github`가 webhook/backfill로 발행하는 repo read-model의 로컬 사본.
 * `GithubAppClient.listOrgRepos` Feign 호출을 대체한다.
 */
@Entity
@Table(name = "tb_github_repo_projections")
class GithubRepoProjection(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(name = "installation_id", nullable = false)
    var installationId: Long,

    @Column(nullable = false, length = 100)
    val owner: String,

    @Column(nullable = false, length = 100)
    val repo: String,

    @Column(name = "full_name", nullable = false, length = 201)
    var fullName: String,

    @Column(name = "is_private", nullable = false)
    var private: Boolean,

    @Column(name = "html_url", nullable = false, length = 512)
    var htmlUrl: String,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(event: GithubRepoStateEvent): Boolean {
        val version = event.sourceOccurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && deleted && !event.deleted)) {
            return false
        }

        installationId = event.installationId
        fullName = event.fullName
        private = event.private
        htmlUrl = event.htmlUrl
        deleted = event.deleted
        sourceOccurredAt = version
        return true
    }

    companion object {
        fun id(owner: String, repo: String): String = "$owner/$repo"
    }
}
