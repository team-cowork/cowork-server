package com.cowork.channel.domain.channel.event

import java.time.Instant

enum class DmParticipantEventType { JOIN, LEAVE }

data class DmParticipantEvent(
    val eventType: DmParticipantEventType,
    val channelId: Long,
    val teamId: Long?,
    val userId: Long,
    val role: String,
    val channelType: String,
    val occurredAt: Instant = Instant.now(),
    val snapshot: Boolean = false,
)
