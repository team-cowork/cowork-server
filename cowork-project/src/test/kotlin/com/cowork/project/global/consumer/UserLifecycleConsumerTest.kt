package com.cowork.project.global.consumer

import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import java.time.Instant

class UserLifecycleConsumerTest {
    private val handler = mockk<ProjectLifecycleHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = UserLifecycleConsumer(handler, objectMapper, processor, streams)

    @Test
    fun `USER_DELETED는 userId key와 Instant occurredAt 계약이 유효할 때만 반영한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00.123456789Z")
        val payload = UserLifecyclePayload("USER_DELETED", 7L, occurredAt)
        every { objectMapper.readValue(any<String>(), UserLifecyclePayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord(Topics.USER_LIFECYCLE, 0, 1L, "7", "{}"))
        consumer.consume(ConsumerRecord(Topics.USER_LIFECYCLE, 0, 2L, "8", "{}"))

        verify(exactly = 1) { handler.onUserDeleted(7L, occurredAt) }
    }
}
