package com.cowork.preference.domain

import java.time.Instant

data class TeamMemberProjection(
    val teamId: Long,
    val accountId: Long,
    val builtInRole: String,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
)
