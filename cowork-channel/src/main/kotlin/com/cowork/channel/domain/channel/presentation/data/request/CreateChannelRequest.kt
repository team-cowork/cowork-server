package com.cowork.channel.domain.channel.presentation.data.request

data class CreateChannelRequest(
    val teamId: Long,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
)
