package com.cowork.channel.domain.webhook.repository

import com.cowork.channel.domain.webhook.entity.Webhook
import org.springframework.data.jpa.repository.JpaRepository

interface WebhookRepository : JpaRepository<Webhook, Long> {

    fun findByChannelId(channelId: Long): List<Webhook>
}
