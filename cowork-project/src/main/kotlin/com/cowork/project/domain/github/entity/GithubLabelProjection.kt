package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubLabelStateEvent
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `cowork-github`가 webhook/backfill로 발행하는 라벨 read-model의 로컬 사본.
 * `GithubAppClient.listLabels` Feign 호출을 대체한다.
 */
@Entity
@Table(name = "tb_github_label_projections")
class GithubLabelProjection(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(nullable = false, length = 100)
    val owner: String,

    @Column(nullable = false, length = 100)
    val repo: String,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, length = 10)
    var color: String,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(event: GithubLabelStateEvent): Boolean {
        val version = event.sourceOccurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && deleted && !event.deleted)) {
            return false
        }

        color = event.color
        deleted = event.deleted
        sourceOccurredAt = version
        return true
    }

    companion object {
        fun id(owner: String, repo: String, name: String): String = "$owner/$repo:$name"
    }
}
