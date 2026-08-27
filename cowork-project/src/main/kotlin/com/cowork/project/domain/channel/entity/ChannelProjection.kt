package com.cowork.project.domain.channel.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_channel_projections")
class ChannelProjection(
    @Id
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "project_id")
    var projectId: Long?,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun apply(projectId: Long?, deleted: Boolean, occurredAt: Instant): Boolean {
        val version = occurredAt.toProjectionPrecision()
        val currentVersion = sourceOccurredAt.toProjectionPrecision()
        if (currentVersion.isAfter(version) || (currentVersion == version && this.deleted && !deleted)) {
            return false
        }

        this.projectId = projectId
        this.deleted = deleted
        sourceOccurredAt = version
        return true
    }
}
