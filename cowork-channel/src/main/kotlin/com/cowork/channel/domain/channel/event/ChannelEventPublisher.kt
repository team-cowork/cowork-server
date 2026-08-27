package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelEventState
import com.cowork.channel.domain.channel.repository.ChannelEventStateRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private const val TOPIC = "channel.event"

@Component
@Transactional(propagation = Propagation.MANDATORY)
class ChannelEventPublisher(
    private val channelRepository: ChannelRepository,
    private val stateRepository: ChannelEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    fun publishCreated(channel: Channel, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("CREATED", channel, deleted = false, requestedAt = requestedAt)

    fun publishUpdated(channel: Channel, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("UPDATED", channel, deleted = false, requestedAt = requestedAt)

    fun publishDeleted(channel: Channel, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("DELETED", channel, deleted = true, requestedAt = requestedAt)

    fun publishSnapshot(state: ChannelEventState) {
        entityManager.flush()
        enqueue(
            eventType = if (state.deleted) "DELETED" else "UPDATED",
            state = state,
            snapshot = true,
        )
    }

    private fun publishMutation(eventType: String, channel: Channel, deleted: Boolean, requestedAt: Instant): Instant {
        val lockedChannel = checkNotNull(channelRepository.findByIdForUpdate(channel.id)) {
            "Channel row must exist while publishing its state mutation: ${channel.id}"
        }
        val existingState = stateRepository.findByChannelIdForUpdate(lockedChannel.id)
        val state = existingState ?: ChannelEventState.create(lockedChannel, deleted, requestedAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(lockedChannel, deleted, requestedAt)
        }
        stateRepository.save(state)
        entityManager.flush()
        enqueue(eventType, state, snapshot = false)
        return version
    }

    private fun enqueue(eventType: String, state: ChannelEventState, snapshot: Boolean) {
        val event = ChannelEvent(
            eventType = eventType,
            channelId = state.channelId,
            teamId = state.teamId,
            projectId = state.projectId,
            name = state.name,
            type = state.type.name,
            viewType = state.viewType.name,
            description = state.description,
            isPrivate = state.isPrivate,
            position = state.position,
            occurredAt = state.stateOccurredAt,
            snapshot = snapshot,
        )
        outboxWriter.enqueue(TOPIC, state.channelId.toString(), event)
    }
}
