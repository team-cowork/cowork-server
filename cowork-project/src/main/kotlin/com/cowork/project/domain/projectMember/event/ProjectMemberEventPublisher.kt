package com.cowork.project.domain.projectMember.event

import com.cowork.project.domain.project.event.nextMonotonicStateVersion
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.repository.ProjectMemberEventTombstoneRepository
import com.cowork.project.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "project.member.event"

@Component
class ProjectMemberEventPublisher(
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
    private val tombstoneRepository: ProjectMemberEventTombstoneRepository,
) {
    fun publishAdded(member: ProjectMember, occurredAt: Instant = Instant.now()) {
        val tombstone = tombstoneRepository.findByProjectIdAndUserIdForUpdate(member.projectId, member.userId)
        val currentVersion = maxOf(member.stateOccurredAt, tombstone?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextMonotonicStateVersion(currentVersion, occurredAt)
        member.stateOccurredAt = eventVersion
        tombstone?.let(tombstoneRepository::delete)
        publish(ProjectMemberEventType.ADDED, member.projectId, member.userId, eventVersion)
    }

    fun publishRemoved(member: ProjectMember, occurredAt: Instant = Instant.now()) {
        val tombstone = tombstoneRepository.findByProjectIdAndUserIdForUpdate(member.projectId, member.userId)
        val currentVersion = maxOf(member.stateOccurredAt, tombstone?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextMonotonicStateVersion(currentVersion, occurredAt)
        member.stateOccurredAt = eventVersion
        saveTombstone(tombstone, member.projectId, member.userId, eventVersion)
        publish(ProjectMemberEventType.REMOVED, member.projectId, member.userId, eventVersion)
    }

    fun publishRemoved(projectId: Long, userId: Long, occurredAt: Instant = Instant.now()) {
        val tombstone = tombstoneRepository.findByProjectIdAndUserIdForUpdate(projectId, userId)
        val eventVersion = nextMonotonicStateVersion(tombstone?.stateOccurredAt ?: Instant.EPOCH, occurredAt)
        saveTombstone(tombstone, projectId, userId, eventVersion)
        publish(ProjectMemberEventType.REMOVED, projectId, userId, eventVersion)
    }

    fun publishSnapshot(member: ProjectMember) = publish(
        ProjectMemberEventType.ADDED,
        member.projectId,
        member.userId,
        member.stateOccurredAt,
        snapshot = true,
    )

    fun publishSnapshot(tombstone: ProjectMemberEventTombstone) = publish(
        ProjectMemberEventType.REMOVED,
        tombstone.projectId,
        tombstone.userId,
        tombstone.stateOccurredAt,
        snapshot = true,
    )

    private fun saveTombstone(
        tombstone: ProjectMemberEventTombstone?,
        projectId: Long,
        userId: Long,
        version: Instant,
    ) {
        if (tombstone == null) {
            tombstoneRepository.save(
                ProjectMemberEventTombstone(
                    projectId = projectId,
                    userId = userId,
                    stateOccurredAt = version,
                ),
            )
        } else {
            tombstone.stateOccurredAt = version
        }
    }

    private fun publish(
        eventType: ProjectMemberEventType,
        projectId: Long,
        userId: Long,
        occurredAt: Instant,
        snapshot: Boolean = false,
    ) {
        val event = ProjectMemberEvent(eventType, projectId, userId, occurredAt, snapshot)
        val eventKey = "$projectId:$userId"
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, eventKey, event)
    }
}
