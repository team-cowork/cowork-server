package com.cowork.team.domain.team.event

import java.time.Instant

data class TeamMemberEvent(
    val eventType: String,
    val teamId: Long,
    val userId: Long,
    val role: String,
    val teamName: String,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
)
