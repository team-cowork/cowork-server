package com.cowork.channel.domain.channel.presentation.data.request

data class UpdateChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val isPrivate: Boolean? = null,
    val projectId: Long? = null,
)
