package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.DmParticipantEventState
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.repository.DmParticipantEventStateRepository
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class DmParticipantEventPublisher(
    private val channelRepository: ChannelRepository,
    private val memberRepository: DmParticipantRepository,
    private val stateRepository: DmParticipantEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishJoin(
        channelId: Long,
        userId: Long,
        role: String = "MEMBER",
        requestedAt: Instant = Instant.now(),
    ): Instant = publishMutation(
        DmParticipantEventType.JOIN,
        channelId,
        userId,
        role,
        deleted = false,
        requestedAt = requestedAt,
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishLeave(
        channelId: Long,
        userId: Long,
        role: String = "MEMBER",
        requestedAt: Instant = Instant.now(),
    ): Instant = publishMutation(
        DmParticipantEventType.LEAVE,
        channelId,
        userId,
        role,
        deleted = true,
        requestedAt = requestedAt,
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishSnapshot(state: DmParticipantEventState) {
        check(state.channelType == "DM") { "Only DM participation can be published" }
        entityManager.flush()
        enqueue(state, snapshot = true)
    }

    private fun publishMutation(
        eventType: DmParticipantEventType,
        channelId: Long,
        userId: Long,
        role: String,
        deleted: Boolean,
        requestedAt: Instant,
    ): Instant {
        val channel = checkNotNull(channelRepository.findByIdForUpdate(channelId)) {
            "Channel row must exist while publishing its participant state mutation: $channelId"
        }
        check(channel.teamId == null && channel.type.name == "DM") { "Only DM participation has stored participants" }
        val member = memberRepository.findByChannelIdAndUserIdForUpdate(channelId, userId)
        check(eventType == DmParticipantEventType.LEAVE || member != null) {
            "DM participant row must exist while publishing a JOIN mutation: $channelId:$userId"
        }
        val existingState = stateRepository.findByKeyForUpdate(channelId, userId)
        val state = existingState ?: DmParticipantEventState.create(channel, userId, role, deleted, requestedAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(channel, role, deleted, requestedAt)
        }
        stateRepository.save(state)
        entityManager.flush()
        check(
            (eventType == DmParticipantEventType.LEAVE) == state.deleted,
        ) { "DM participant event type must match its persisted deletion state" }
        enqueue(state, snapshot = false)
        return version
    }

    private fun enqueue(state: DmParticipantEventState, snapshot: Boolean) {
        val event = DmParticipantEvent(
            eventType = if (state.deleted) DmParticipantEventType.LEAVE else DmParticipantEventType.JOIN,
            channelId = state.channelId,
            teamId = state.teamId,
            userId = state.userId,
            role = state.role,
            channelType = state.channelType,
            occurredAt = state.stateOccurredAt,
            snapshot = snapshot,
        )
        val eventKey = "${state.channelId}:${state.userId}"
        outboxWriter.enqueue(TOPIC, eventKey, event)
    }

    companion object {
        const val TOPIC = "channel.dm-participant.state.v1"
    }
}
