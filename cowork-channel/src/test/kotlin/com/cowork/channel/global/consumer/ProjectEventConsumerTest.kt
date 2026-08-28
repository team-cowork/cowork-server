package com.cowork.channel.global.consumer

import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectEventConsumerTest {

    private val handler = mockk<ProjectProjectionHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = ProjectEventConsumer(handler, objectMapper, processor, streams)
    private val payload = ProjectEventPayload("UPDATED", 7L, 3L, Instant.parse("2026-08-26T03:00:00Z"))

    init {
        every { objectMapper.readValue(any<String>(), ProjectEventPayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }
    }

    @Test
    fun `projectId와 같은 key의 이벤트만 projection에 반영한다`() {
        consumer.consume(ConsumerRecord(Topics.PROJECT_EVENT, 0, 1L, "7", "{}"))
        consumer.consume(ConsumerRecord(Topics.PROJECT_EVENT, 0, 2L, "3", "{}"))

        verify(exactly = 1) { handler.apply(payload) }
    }
}
