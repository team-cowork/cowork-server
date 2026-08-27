package com.cowork.project.domain.githubPreference.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_github_repo_preference_projections")
class GithubRepoPreferenceProjection(
    @Id
    @Column(name = "repo_id", nullable = false)
    val repoId: Long,

    @Column(name = "label_auto_apply", nullable = false)
    var labelAutoApply: Boolean,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(labelAutoApply: Boolean, deleted: Boolean, occurredAt: Instant): Boolean {
        val version = occurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && this.deleted && !deleted)) {
            return false
        }

        this.labelAutoApply = labelAutoApply
        this.deleted = deleted
        sourceOccurredAt = version
        return true
    }
}
