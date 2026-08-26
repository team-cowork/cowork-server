package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.toProjectionSourceInstant
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "project.event"

@Component
class ProjectEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishCreated(project: Project, occurredAt: Instant = Instant.now()) =
        publish(ProjectEventType.CREATED, project, occurredAt)

    fun publishUpdated(project: Project, occurredAt: Instant = Instant.now()) =
        publish(ProjectEventType.UPDATED, project, occurredAt)

    fun publishDeleted(project: Project, occurredAt: Instant = Instant.now()) =
        publish(ProjectEventType.DELETED, project, occurredAt)

    fun publishSnapshot(project: Project) =
        publish(ProjectEventType.UPDATED, project, project.updatedAt.toProjectionSourceInstant(), snapshot = true)

    private fun publish(
        eventType: ProjectEventType,
        project: Project,
        occurredAt: Instant,
        snapshot: Boolean = false,
    ) {
        val event = ProjectEvent(
            eventType = eventType,
            projectId = project.id,
            teamId = project.teamId,
            name = project.name,
            description = project.description,
            status = project.status,
            position = project.position,
            occurredAt = occurredAt,
            snapshot = snapshot,
        )
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, project.id.toString(), event)
    }
}
