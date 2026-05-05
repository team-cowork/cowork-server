package com.cowork.channel.dto

data class UpdateChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val isPrivate: Boolean? = null,
)
