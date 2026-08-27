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

class ChannelStateConsumerTest {

    private val handler = mockk<ChannelProjectionHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = ChannelStateConsumer(handler, objectMapper, processor, streams)

    @Test
    fun `control record는 processControlRecord에서 소비되고 이후 로직을 타지 않는다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns true
        val record = ConsumerRecord("channel.event", 0, 1L, "__snapshot__:0", "{}")

        consumer.consume(record)

        verify(exactly = 0) { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `key와 필드가 유효한 이벤트만 반영한다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = ChannelStatePayload("UPDATED", 100L, 1L, occurredAt)
        every { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "100", "{}"))

        verify(exactly = 1) { handler.apply(100L, 1L, false, occurredAt) }
    }

    @Test
    fun `DELETED eventType은 deleted true로 반영한다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = ChannelStatePayload("DELETED", 100L, null, occurredAt)
        every { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "100", "{}"))

        verify(exactly = 1) { handler.apply(100L, null, true, occurredAt) }
    }

    @Test
    fun `channelId와 Kafka key가 다르면 격리하고 handler를 호출하지 않는다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        val payload = ChannelStatePayload("UPDATED", 100L, 1L, Instant.parse("2026-08-26T03:00:00Z"))
        every { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "999", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.channelState, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `JSON 역직렬화에 실패하면 격리한다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        every {
            objectMapper.readValue(any<String>(), ChannelStatePayload::class.java)
        } throws RuntimeException("broken json")

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "100", "not-json"))

        verify(exactly = 1) { processor.quarantineRecord(streams.channelState, any(), any()) }
    }

    @Test
    fun `지원하지 않는 eventType은 격리한다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        val payload = ChannelStatePayload("UNKNOWN", 100L, 1L, Instant.parse("2026-08-26T03:00:00Z"))
        every { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "100", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.channelState, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `occurredAt이 없으면 격리한다`() {
        every { processor.processControlRecord(streams.channelState, any()) } returns false
        val payload = ChannelStatePayload("UPDATED", 100L, 1L, null)
        every { objectMapper.readValue(any<String>(), ChannelStatePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("channel.event", 0, 1L, "100", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.channelState, any(), any()) }
    }
}
