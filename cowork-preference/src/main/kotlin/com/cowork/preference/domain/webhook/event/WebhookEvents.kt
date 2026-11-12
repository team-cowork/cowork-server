package com.cowork.preference.domain.webhook.event

import com.cowork.preference.domain.webhook.model.Webhook
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics

object WebhookEvents {
    fun webhookState(webhook: Webhook, snapshot: Boolean): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.WEBHOOK_STATE_TOPIC,
        key = webhook.id.toString(),
        payload = basePayload("WEBHOOK_STATE", webhook.updatedAt)
            .put("schemaVersion", 2).put("webhookId", webhook.id).put("channelId", webhook.channelId)
            .put("executionId", webhook.executionId.toString())
            .put("name", webhook.name).put("avatarUrl", webhook.avatarUrl).put("isSecure", webhook.isSecure)
            .put("secretHash", webhook.secretHash).put("isActive", webhook.isActive)
            .put("deleted", webhook.deleted).put("snapshot", snapshot),
        occurredAt = webhook.updatedAt,
    )
}
