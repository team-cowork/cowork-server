package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectEventTombstoneRepository
import com.cowork.project.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "project.event"

@Component
class ProjectEventPublisher(
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
    private val tombstoneRepository: ProjectEventTombstoneRepository,
) {
    fun publishCreated(project: Project, occurredAt: Instant = Instant.now()) = publishActive(
        ProjectEventType.CREATED,
        project,
        occurredAt,
    )

    fun publishUpdated(project: Project, occurredAt: Instant = Instant.now()) = publishActive(
        ProjectEventType.UPDATED,
        project,
        occurredAt,
    )

    fun publishDeleted(project: Project, occurredAt: Instant = Instant.now()) {
        val tombstone = tombstoneRepository.findByProjectIdForUpdate(project.id)
        val currentVersion = maxOf(project.stateOccurredAt, tombstone?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextMonotonicStateVersion(currentVersion, occurredAt)
        project.stateOccurredAt = eventVersion
        if (tombstone == null) {
            tombstoneRepository.save(ProjectEventTombstone.from(project, eventVersion))
        } else {
            tombstone.replaceFrom(project, eventVersion)
        }
        publish(ProjectEventType.DELETED, project, eventVersion)
    }

    fun publishSnapshot(project: Project) = publish(
        ProjectEventType.UPDATED,
        project,
        project.stateOccurredAt,
        snapshot = true,
    )

    fun publishSnapshot(tombstone: ProjectEventTombstone) {
        val event = ProjectEvent(
            eventType = ProjectEventType.DELETED,
            projectId = tombstone.projectId,
            teamId = tombstone.teamId,
            name = tombstone.name,
            description = tombstone.description,
            status = tombstone.status,
            position = tombstone.position,
            occurredAt = tombstone.stateOccurredAt,
            snapshot = true,
        )
        enqueue(tombstone.projectId, event)
    }

    private fun publishActive(eventType: ProjectEventType, project: Project, occurredAt: Instant) {
        val tombstone = tombstoneRepository.findByProjectIdForUpdate(project.id)
        val currentVersion = maxOf(project.stateOccurredAt, tombstone?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextMonotonicStateVersion(currentVersion, occurredAt)
        project.stateOccurredAt = eventVersion
        tombstone?.let(tombstoneRepository::delete)
        publish(eventType, project, eventVersion)
    }

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
        enqueue(project.id, event)
    }

    private fun enqueue(projectId: Long, event: ProjectEvent) {
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, projectId.toString(), event)
    }
}
