package com.cowork.channel.domain.project.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_project_projections")
class ProjectProjection(
    @Id
    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyUpsert(teamId: Long, occurredAt: Instant) {
        this.teamId = teamId
        deleted = false
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        deleted = true
        sourceOccurredAt = occurredAt
    }
}
