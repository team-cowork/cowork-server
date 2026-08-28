package com.cowork.channel.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProjectEventPayload(
    val eventType: String,
    val projectId: Long,
    val teamId: Long,
    val occurredAt: Instant? = null,
)
