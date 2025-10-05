package com.cowork.channel.repository

import com.cowork.channel.domain.Webhook
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface WebhookRepository : JpaRepository<Webhook, Long> {

    fun findAllByChannelIdOrderByIdAsc(channelId: Long): List<Webhook>

    fun findByIdAndChannelId(id: Long, channelId: Long): Webhook?

    @Modifying
    @Query("DELETE FROM Webhook w WHERE w.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
