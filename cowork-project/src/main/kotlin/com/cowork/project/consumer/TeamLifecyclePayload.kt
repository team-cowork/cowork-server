package com.cowork.project.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamLifecyclePayload(
    val eventType: String,
    val teamId: Long,
    val teamName: String? = null,
    val actorUserId: Long? = null,
    val targetUserIds: List<Long> = emptyList(),
    val occurredAt: LocalDateTime? = null,
    val newRole: String? = null,
)
