package com.cowork.channel.dto

data class CreateChannelRequest(
    val teamId: Long,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
)
