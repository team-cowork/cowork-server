package com.cowork.team.domain.team.event

import com.cowork.team.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TeamEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishNotification(teamId: Long, event: NotificationTriggerEvent) {
        entityManager.flush()
        outboxWriter.enqueue(Topics.NOTIFICATION_TRIGGER, teamId.toString(), event)
    }

    fun publishLifecycle(payload: TeamEventPayload) = send(Topics.TEAM_LIFECYCLE, payload)

    fun publishTeamSnapshot(
        teamId: Long,
        teamName: String,
        actorUserId: Long,
        targetUserIds: List<Long>,
        occurredAt: Instant,
    ) {
        publishLifecycle(
            TeamEventPayload(
                eventType = "TEAM_UPDATED",
                teamId = teamId,
                teamName = teamName,
                actorUserId = actorUserId,
                targetUserIds = targetUserIds,
                occurredAt = occurredAt,
                snapshot = true,
            ),
        )
    }

    fun publishMemberInvited(
        teamId: Long,
        teamName: String,
        actorUserId: Long,
        targetUserIds: List<Long>,
        occurredAt: Instant = Instant.now(),
    ) {
        if (targetUserIds.isEmpty()) return
        publishLifecycle(
            TeamEventPayload(
                eventType = "MEMBER_INVITED",
                teamId = teamId,
                teamName = teamName,
                actorUserId = actorUserId,
                targetUserIds = targetUserIds.distinct(),
                occurredAt = occurredAt,
            ),
        )
    }

    fun publishMemberJoined(teamId: Long, teamName: String, userId: Long) {
        publishLifecycle(
            TeamEventPayload(
                eventType = "MEMBER_JOINED",
                teamId = teamId,
                teamName = teamName,
                actorUserId = userId,
                targetUserIds = listOf(userId),
            ),
        )
    }

    fun publishRoleChanged(
        teamId: Long,
        teamName: String,
        actorUserId: Long,
        targetUserIds: List<Long>,
        newRole: String,
        occurredAt: Instant = Instant.now(),
    ) {
        if (targetUserIds.isEmpty()) return
        publishLifecycle(
            TeamEventPayload(
                eventType = "ROLE_CHANGED",
                teamId = teamId,
                teamName = teamName,
                actorUserId = actorUserId,
                targetUserIds = targetUserIds.distinct(),
                occurredAt = occurredAt,
                newRole = newRole,
            ),
        )
    }

    private fun send(topic: String, payload: TeamEventPayload) {
        entityManager.flush()
        outboxWriter.enqueue(topic, payload.teamId.toString(), payload)
    }
}
