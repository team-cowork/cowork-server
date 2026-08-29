package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ChannelRolePolicyProjectionHandler(private val repository: ChannelRolePolicyProjectionRepository) {
    @Transactional
    fun handle(sourceEvent: ChannelRolePolicyChangedEvent) {
        val event = sourceEvent.copy(occurredAt = sourceEvent.occurredAt.toProjectionPrecision())
        val key = ChannelRolePolicyProjection.key(event.teamId, event.channelId, event.roleId)
        val existing = repository.findById(key).orElse(null)
        val existingVersion = existing?.sourceOccurredAt?.toProjectionPrecision()
        if (existingVersion?.isAfter(event.occurredAt) == true ||
            (existingVersion == event.occurredAt && existing.deleted)
        ) {
            return
        }
        val projection = existing ?: ChannelRolePolicyProjection(
            policyKey = key,
            teamId = event.teamId,
            channelId = event.channelId,
            roleId = event.roleId,
            sourceOccurredAt = event.occurredAt,
        )
        check(
            projection.teamId == event.teamId &&
                projection.channelId == event.channelId &&
                projection.roleId == event.roleId,
        ) { "같은 policy key의 식별자를 변경할 수 없습니다." }
        when (event.eventType) {
            "UPSERT" -> projection.applyUpsert(
                messageRead = requireNotNull(requireNotNull(event.permissions)[MESSAGE_READ_KEY]),
                occurredAt = event.occurredAt,
            )
            "DELETE" -> projection.markDeleted(event.occurredAt)
        }
        repository.save(projection)
    }

    private companion object {
        const val MESSAGE_READ_KEY = "message_read"
    }
}
