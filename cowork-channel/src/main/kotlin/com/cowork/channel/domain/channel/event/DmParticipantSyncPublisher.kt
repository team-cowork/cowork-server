package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.DmParticipant
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class DmParticipantSyncPublisher(private val dmParticipantEventPublisher: DmParticipantEventPublisher) {
    fun publishChannelSnapshot(channel: Channel, members: List<DmParticipant>, requestedAt: Instant = Instant.now()) {
        members.forEach { member ->
            dmParticipantEventPublisher.publishJoin(
                channelId = channel.id,
                userId = member.userId,
                requestedAt = requestedAt,
            )
        }
    }
}
