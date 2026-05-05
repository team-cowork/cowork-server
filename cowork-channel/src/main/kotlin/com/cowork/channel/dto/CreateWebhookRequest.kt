package com.cowork.channel.dto

data class CreateWebhookRequest(
    val name: String,
    val isSecure: Boolean = false,
    val avatarUrl: String? = null,
)
