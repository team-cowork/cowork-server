package com.cowork.channel.client

data class TeamMembershipResponse(
    val teamId: Long,
    val userId: Long,
    val role: String,
)
