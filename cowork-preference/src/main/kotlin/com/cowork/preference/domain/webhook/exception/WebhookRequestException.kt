package com.cowork.preference.domain.webhook.exception

import java.time.Instant

class WebhookRequestException(
    val status: Int,
    message: String,
    val retryAfter: Long? = null,
    val nextAllowedAt: Instant? = null,
) : RuntimeException(message)
