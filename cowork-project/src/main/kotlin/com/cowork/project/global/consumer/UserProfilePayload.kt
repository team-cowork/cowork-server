package com.cowork.project.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfilePayload(
    val eventType: String,
    val userId: Long,
    val githubId: String? = null,
    val occurredAt: Instant? = null,
)
