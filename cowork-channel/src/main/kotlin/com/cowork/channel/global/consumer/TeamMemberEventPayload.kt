package com.cowork.channel.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamMemberEventPayload(
    val eventType: String,
    val teamId: Long,
    val userId: Long,
    val role: String = "MEMBER",
    val occurredAt: Instant? = null,
)
