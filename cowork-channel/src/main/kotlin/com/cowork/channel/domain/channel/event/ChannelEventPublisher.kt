package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.global.outbox.OutboxWriter
import com.cowork.channel.global.projection.toProjectionSourceInstant
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

private const val TOPIC = "channel.event"

@Component
class ChannelEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {
    fun publishCreated(channel: Channel, occurredAt: Instant = Instant.now()) = publish("CREATED", channel, occurredAt)

    fun publishUpdated(channel: Channel, occurredAt: Instant = Instant.now()) = publish("UPDATED", channel, occurredAt)

    fun publishDeleted(channel: Channel, occurredAt: Instant = Instant.now()) = publish("DELETED", channel, occurredAt)

    fun publishSnapshot(channel: Channel) =
        publish("UPDATED", channel, channel.updatedAt.toProjectionSourceInstant(), snapshot = true)

    private fun publish(eventType: String, channel: Channel, occurredAt: Instant, snapshot: Boolean = false) {
        val event = ChannelEvent(
            eventType = eventType,
            channelId = channel.id,
            teamId = channel.teamId,
            projectId = channel.projectId,
            name = channel.name,
            type = channel.type.name,
            viewType = channel.viewType.name,
            description = channel.description,
            isPrivate = channel.isPrivate,
            position = channel.position,
            occurredAt = occurredAt,
            snapshot = snapshot,
        )
        entityManager.flush()
        outboxWriter.enqueue(TOPIC, channel.id.toString(), event)
    }
}
