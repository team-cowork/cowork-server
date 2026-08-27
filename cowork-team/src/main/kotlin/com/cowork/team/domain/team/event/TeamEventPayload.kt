package com.cowork.team.domain.team.event

import java.time.Instant

data class TeamEventPayload(
    val eventType: String,
    val teamId: Long,
    val teamName: String,
    val actorUserId: Long,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
    val description: String? = null,
    val iconUrl: String? = null,
    val ownerUserId: Long? = null,
    val githubInstallationId: Long? = null,
    val githubOrgLogin: String? = null,
)
