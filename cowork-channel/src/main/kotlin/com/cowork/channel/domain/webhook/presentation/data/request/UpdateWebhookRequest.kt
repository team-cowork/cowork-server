package com.cowork.channel.domain.webhook.presentation.data.request

data class UpdateWebhookRequest(val name: String? = null, val avatarUrl: String? = null, val isSecure: Boolean? = null)
