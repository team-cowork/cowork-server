package com.cowork.channel.domain.webhook.repository

import com.cowork.channel.domain.webhook.entity.Webhook
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface WebhookRepository : Repository<Webhook, Long> {
    @Query("SELECT w FROM Webhook w WHERE w.channelId = :channelId AND w.id > :afterId ORDER BY w.id")
    fun findSnapshotBatch(
        @Param("channelId") channelId: Long,
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<Webhook>

    @Query(value = "SELECT UTC_TIMESTAMP(6)", nativeQuery = true)
    fun currentSnapshotTime(): LocalDateTime
}
