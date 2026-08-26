package com.cowork.team.domain.team.event

import java.time.Instant

data class TeamEventPayload(
    val eventType: String,
    val teamId: Long,
    val teamName: String,
    val actorUserId: Long,
    val targetUserIds: List<Long>,
    val occurredAt: Instant = Instant.now(),
    val newRole: String? = null,
    val snapshot: Boolean = false,
)
