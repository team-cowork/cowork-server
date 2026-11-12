package com.cowork.preference.domain.access.projection.team.model

import java.time.Instant

data class TeamMemberEvent(
    val eventType: String,
    val teamId: Long,
    val userId: Long,
    val role: String,
    val occurredAt: Instant,
    val authorizationVersion: Instant,
)
