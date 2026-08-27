package com.cowork.project.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserLifecyclePayload(val eventType: String, val userId: Long, val occurredAt: Instant)
