package com.cowork.team.dto

import java.time.LocalDateTime

data class TeamEventPayload(
    val eventType: String,
    val teamId: Long,
    val teamName: String,
    val actorUserId: Long,
    val targetUserIds: List<Long>,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
)
