package com.cowork.channel.dto

data class UpdateWebhookRequest(
    val name: String? = null,
    val avatarUrl: String? = null,
    val isSecure: Boolean? = null,
)
