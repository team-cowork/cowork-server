package com.cowork.project.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelStatePayload(
    val eventType: String,
    val channelId: Long,
    val projectId: Long? = null,
    val occurredAt: Instant? = null,
)
