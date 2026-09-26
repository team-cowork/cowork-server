package com.cowork.project.domain.github.event

import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.stereotype.Component
import java.util.UUID

private const val TOPIC = "notification.trigger"
private const val GITHUB_COMMENT_CREATED = "GITHUB_COMMENT_CREATED"

@Component
class GithubCommentNotificationPublisher(private val outboxWriter: OutboxWriter) {

    fun publishCommentCreated(targetUserId: Long, data: Map<String, Any?>) {
        val event = NotificationTriggerEvent(
            eventId = UUID.randomUUID().toString(),
            type = GITHUB_COMMENT_CREATED,
            targetUserIds = listOf(targetUserId),
            data = data,
        )
        outboxWriter.enqueue(TOPIC, targetUserId.toString(), event)
    }
}
