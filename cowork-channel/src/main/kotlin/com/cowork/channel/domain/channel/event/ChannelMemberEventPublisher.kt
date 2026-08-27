package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.ChannelMemberEventState
import com.cowork.channel.domain.channel.repository.ChannelMemberEventStateRepository
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private const val TOPIC = "channel.member.event"

@Component
@Transactional(propagation = Propagation.MANDATORY)
class ChannelMemberEventPublisher(
    private val channelRepository: ChannelRepository,
    private val memberRepository: ChannelMemberRepository,
    private val stateRepository: ChannelMemberEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    fun publishJoin(
        channelId: Long,
        userId: Long,
        role: String = "MEMBER",
        requestedAt: Instant = Instant.now(),
    ): Instant = publishMutation(
        ChannelMemberEventType.JOIN,
        channelId,
        userId,
        role,
        deleted = false,
        requestedAt = requestedAt,
    )

    fun publishLeave(
        channelId: Long,
        userId: Long,
        role: String = "MEMBER",
        requestedAt: Instant = Instant.now(),
    ): Instant = publishMutation(
        ChannelMemberEventType.LEAVE,
        channelId,
        userId,
        role,
        deleted = true,
        requestedAt = requestedAt,
    )

    fun publishSnapshot(state: ChannelMemberEventState) {
        entityManager.flush()
        enqueue(state, snapshot = true)
    }

    private fun publishMutation(
        eventType: ChannelMemberEventType,
        channelId: Long,
        userId: Long,
        role: String,
        deleted: Boolean,
        requestedAt: Instant,
    ): Instant {
        val channel = checkNotNull(channelRepository.findByIdForUpdate(channelId)) {
            "Channel row must exist while publishing its member state mutation: $channelId"
        }
        val member = memberRepository.findByChannelIdAndUserIdForUpdate(channelId, userId)
        check(eventType == ChannelMemberEventType.LEAVE || member != null) {
            "Channel member row must exist while publishing a JOIN mutation: $channelId:$userId"
        }
        val existingState = stateRepository.findByKeyForUpdate(channelId, userId)
        val state = existingState ?: ChannelMemberEventState.create(channel, userId, role, deleted, requestedAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(channel, role, deleted, requestedAt)
        }
        stateRepository.save(state)
        entityManager.flush()
        check(
            (eventType == ChannelMemberEventType.LEAVE) == state.deleted,
        ) { "Channel member event type must match its persisted deletion state" }
        enqueue(state, snapshot = false)
        return version
    }

    private fun enqueue(state: ChannelMemberEventState, snapshot: Boolean) {
        val event = ChannelMemberEvent(
            eventType = if (state.deleted) ChannelMemberEventType.LEAVE else ChannelMemberEventType.JOIN,
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
}
