package com.cowork.project.domain.projectMember.event

import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.toProjectionSourceInstant
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "project.member.event"

@Component
class ProjectMemberEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishAdded(projectId: Long, userId: Long, occurredAt: Instant = Instant.now(), snapshot: Boolean = false) =
        publish(ProjectMemberEventType.ADDED, projectId, userId, occurredAt, snapshot)

    fun publishRemoved(projectId: Long, userId: Long, occurredAt: Instant = Instant.now(), snapshot: Boolean = false) =
        publish(ProjectMemberEventType.REMOVED, projectId, userId, occurredAt, snapshot)

    fun publishSnapshot(member: ProjectMember) = publish(
        ProjectMemberEventType.ADDED,
        member.projectId,
        member.userId,
        member.joinedAt.toProjectionSourceInstant(),
        snapshot = true,
    )

    private fun publish(
        eventType: ProjectMemberEventType,
        projectId: Long,
        userId: Long,
        occurredAt: Instant,
        snapshot: Boolean,
    ) {
        val event = ProjectMemberEvent(eventType, projectId, userId, occurredAt, snapshot)
        val eventKey = "$projectId:$userId"
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, eventKey, event)
    }
}
