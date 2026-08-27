package com.cowork.project.domain.user.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_user_profile_projections")
class UserProfileProjection(
    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "github_id", length = 255)
    var githubId: String?,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(githubId: String?, deleted: Boolean, occurredAt: Instant): Boolean {
        val version = occurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && this.deleted && !deleted)) {
            return false
        }

        this.githubId = githubId
        this.deleted = deleted
        sourceOccurredAt = version
        return true
    }
}
