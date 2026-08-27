package com.cowork.team.domain.team.event

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.global.outbox.OutboxWriter
import com.cowork.team.global.projection.toProjectionSourceInstant
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TeamMemberEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishUpsert(member: TeamMember, occurredAt: Instant = Instant.now(), snapshot: Boolean = false) =
        publish("UPSERT", member, occurredAt, snapshot)

    fun publishDelete(member: TeamMember, occurredAt: Instant = Instant.now(), snapshot: Boolean = false) =
        publish("DELETE", member, occurredAt, snapshot)

    fun publishSnapshot(member: TeamMember) {
        val sourceTime = requireNotNull(
            listOfNotNull(member.joinedAt, member.updatedAt, member.team.createdAt, member.team.updatedAt).maxOrNull(),
        ) { "팀 멤버 snapshot source timestamp가 없습니다: ${member.team.id}:${member.userId}" }
        publish("UPSERT", member, sourceTime.toProjectionSourceInstant(), snapshot = true)
    }

    private fun publish(eventType: String, member: TeamMember, occurredAt: Instant, snapshot: Boolean) {
        val event = TeamMemberEvent(
            eventType = eventType,
            teamId = member.team.id,
            userId = member.userId,
            role = member.role.name,
            teamName = member.team.name,
            occurredAt = occurredAt,
            snapshot = snapshot,
        )
        val key = "${event.teamId}:${event.userId}"
        entityManager.flush()
        outboxWriter.enqueue(Topics.TEAM_MEMBER_EVENT, key, event)
    }
}
