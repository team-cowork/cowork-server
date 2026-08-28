package com.cowork.team.domain.team.event

data class NotificationTriggerEvent(
    val type: String,
    val targetUserIds: List<Long>,
    val forcedUserIds: List<Long> = emptyList(),
    val data: Map<String, Any> = emptyMap(),
)
