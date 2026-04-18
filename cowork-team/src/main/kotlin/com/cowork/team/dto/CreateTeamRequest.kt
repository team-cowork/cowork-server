package com.cowork.team.dto

data class CreateTeamRequest(
    val name: String,
    val description: String?,
    val iconUrl: String?,
)