package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.entity.ProjectStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_project_event_tombstones")
class ProjectEventTombstone(
    @Id
    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProjectStatus,

    @Column(nullable = false)
    var position: Int,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun replaceFrom(project: Project, version: Instant) {
        teamId = project.teamId
        name = project.name
        description = project.description
        status = project.status
        position = project.position
        stateOccurredAt = version
    }

    companion object {
        fun from(project: Project, version: Instant) = ProjectEventTombstone(
            projectId = project.id,
            teamId = project.teamId,
            name = project.name,
            description = project.description,
            status = project.status,
            position = project.position,
            stateOccurredAt = version,
        )
    }
}
