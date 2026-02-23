package com.cowork.chat

import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.service.ChatMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = ChatMetrics(registry)

    private fun event(webhookId: Long?) = MessageCreatedEvent(
        teamId = 1,
        message = MessageResponse.of(
            Message(channelId = 10, senderId = if (webhookId == null) 1 else null, type = MessageType.TEXT, content = "hi", webhookId = webhookId),
            null,
            emptyList(),
        ),
        mentionedUserIds = emptySet(),
    )

    @Test
    fun `보낸 경로별로 메시지 수를 센다`() {
        metrics.onCreated(event(null))
        metrics.onCreated(event(null))
        metrics.onCreated(event(3))

        assertEquals(2.0, registry.counter(ChatMetrics.MESSAGE_COUNTER, "source", "user").count())
        assertEquals(1.0, registry.counter(ChatMetrics.MESSAGE_COUNTER, "source", "webhook").count())
    }
}
