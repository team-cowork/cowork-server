package com.cowork.channel.repository

import com.cowork.channel.domain.Webhook
import org.springframework.data.jpa.repository.JpaRepository

interface WebhookRepository : JpaRepository<Webhook, Long> {

    fun findByChannelId(channelId: Long): List<Webhook>
}
