package com.cowork.channel.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserLifecyclePayload(val eventType: String, val userId: Long, val occurredAt: LocalDateTime? = null)
