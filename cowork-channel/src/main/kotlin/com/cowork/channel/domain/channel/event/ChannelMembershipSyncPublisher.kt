package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ChannelMembershipSyncPublisher(private val channelMemberEventPublisher: ChannelMemberEventPublisher) {
    fun publishChannelSnapshot(
        channel: Channel,
        members: List<ChannelMember>,
        occurredAt: Instant = Instant.now(),
        snapshot: Boolean = false,
    ) {
        members.forEach { member ->
            channelMemberEventPublisher.publishJoin(
                channelId = channel.id,
                teamId = channel.teamId,
                userId = member.userId,
                channelType = channel.type.name,
                occurredAt = occurredAt,
                snapshot = snapshot,
            )
        }
    }
}
