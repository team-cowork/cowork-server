package com.cowork.team.global.outbox

import org.springframework.modulith.NamedInterface
import java.util.UUID

@NamedInterface("outbox", propagate = false)
data class NotificationTriggerEvent(
    val type: String,
    val targetUserIds: List<Long>,
    val forcedUserIds: List<Long> = emptyList(),
    val data: Map<String, Any> = emptyMap(),
    val eventId: String = UUID.randomUUID().toString(),
)
