package com.cowork.preference.domain.access.projection.team

import com.cowork.preference.domain.access.model.authorizationVersion
import java.time.Instant

data class TeamMemberProjection(
    val teamId: Long,
    val accountId: Long,
    val builtInRole: String,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    val authorizationVersion: Instant? = null,
)
