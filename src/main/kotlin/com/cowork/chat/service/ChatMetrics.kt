package com.cowork.chat.service

import com.cowork.chat.event.MessageCreatedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 채널에 저장된 메시지 수를 보낸 경로(사용자, 웹훅)별로 센다.
 */
@Component
class ChatMetrics(
    private val meterRegistry: MeterRegistry,
) {

    @TransactionalEventListener
    fun onCreated(event: MessageCreatedEvent) {
        val source = if (event.message.webhookId != null) "webhook" else "user"
        meterRegistry.counter(MESSAGE_COUNTER, "source", source).increment()
    }

    companion object {
        const val MESSAGE_COUNTER = "cowork.chat.messages"
    }
}
