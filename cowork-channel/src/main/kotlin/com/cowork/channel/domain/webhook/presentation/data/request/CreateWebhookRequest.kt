package com.cowork.channel.domain.webhook.presentation.data.request

data class CreateWebhookRequest(val name: String, val isSecure: Boolean = false, val avatarUrl: String? = null)
