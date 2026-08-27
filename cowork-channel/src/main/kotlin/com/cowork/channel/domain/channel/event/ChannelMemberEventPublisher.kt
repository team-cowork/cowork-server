package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.global.outbox.OutboxWriter
import com.cowork.channel.global.projection.toProjectionSourceInstant
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "channel.member.event"

@Component
class ChannelMemberEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishJoin(
        channelId: Long,
        teamId: Long?,
        userId: Long,
        channelType: String,
        role: String = "MEMBER",
        occurredAt: Instant = Instant.now(),
        snapshot: Boolean = false,
    ) = publish(
        ChannelMemberEvent(
            ChannelMemberEventType.JOIN,
            channelId,
            teamId,
            userId,
            role,
            channelType,
            occurredAt,
            snapshot,
        ),
    )

    fun publishLeave(
        channelId: Long,
        teamId: Long?,
        userId: Long,
        channelType: String,
        role: String = "MEMBER",
        occurredAt: Instant = Instant.now(),
        snapshot: Boolean = false,
    ) = publish(
        ChannelMemberEvent(
            ChannelMemberEventType.LEAVE,
            channelId,
            teamId,
            userId,
            role,
            channelType,
            occurredAt,
            snapshot,
        ),
    )

    fun publishSnapshot(channel: Channel, member: ChannelMember) = publishJoin(
        channelId = channel.id,
        teamId = channel.teamId,
        userId = member.userId,
        channelType = channel.type.name,
        occurredAt = member.joinedAt.toProjectionSourceInstant(),
        snapshot = true,
    )

    private fun publish(event: ChannelMemberEvent) {
        val eventKey = "${event.channelId}:${event.userId}"
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, eventKey, event)
    }
}
