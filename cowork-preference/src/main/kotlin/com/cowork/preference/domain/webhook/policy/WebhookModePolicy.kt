package com.cowork.preference.domain.webhook.policy

import com.cowork.preference.domain.webhook.model.Webhook
import java.time.Duration
import java.time.Instant

object WebhookModePolicy {
    const val COOLDOWN_SECONDS = 30L

    /** The same mode is a no-op, including while a previous transition is cooling down. */
    fun retryAfterSeconds(webhook: Webhook, target: Boolean, now: Instant): Long {
        if (webhook.isSecure == target) return 0
        val previous = webhook.modeChangedAt ?: return 0
        val remaining = Duration.between(now, previous.plusSeconds(COOLDOWN_SECONDS))
        return if (remaining.isNegative || remaining.isZero) 0 else remaining.seconds + if (remaining.nano > 0) 1 else 0
    }
}
