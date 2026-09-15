package com.cowork.team.domain.team.event

import java.util.UUID

data class NotificationTriggerEvent(
    val type: String,
    val targetUserIds: List<Long>,
    val forcedUserIds: List<Long> = emptyList(),
    val data: Map<String, Any> = emptyMap(),
    val eventId: String = UUID.randomUUID().toString(),
)
