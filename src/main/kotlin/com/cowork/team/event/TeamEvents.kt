package com.cowork.team.event

data class TeamDeletedEvent(
    val teamId: Long,
)

data class TeamMemberAddedEvent(
    val teamId: Long,
    val teamName: String,
    val userId: Long,
    val invitedBy: Long,
)

data class TeamMemberRemovedEvent(
    val teamId: Long,
    val userId: Long,
)
