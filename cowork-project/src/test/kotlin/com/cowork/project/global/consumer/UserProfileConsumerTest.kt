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

class UserProfileConsumerTest {

    private val handler = mockk<UserProfileProjectionHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = UserProfileConsumer(handler, objectMapper, processor, streams)

    @Test
    fun `control record는 processControlRecord에서 소비되고 이후 로직을 타지 않는다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns true
        val record = ConsumerRecord("user.profile.event", 0, 1L, "__snapshot__:0", "{}")

        consumer.consume(record)

        verify(exactly = 0) { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `key와 필드가 유효한 UPSERT 이벤트만 반영한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = UserProfilePayload("UPSERT", 7L, "github-user", occurredAt)
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "{}"))

        verify(exactly = 1) { handler.apply(7L, "github-user", false, occurredAt) }
    }

    @Test
    fun `DELETE eventType은 githubId를 무시하고 deleted true로 반영한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = UserProfilePayload("DELETE", 7L, "stale-github-user", occurredAt)
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "{}"))

        verify(exactly = 1) { handler.apply(7L, null, true, occurredAt) }
    }

    @Test
    fun `userId와 Kafka key가 다르면 격리하고 handler를 호출하지 않는다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val payload = UserProfilePayload("UPSERT", 7L, "github-user", Instant.parse("2026-08-26T03:00:00Z"))
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "999", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.userProfile, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `JSON 역직렬화에 실패하면 격리한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        every {
            objectMapper.readValue(any<String>(), UserProfilePayload::class.java)
        } throws RuntimeException("broken json")

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "not-json"))

        verify(exactly = 1) { processor.quarantineRecord(streams.userProfile, any(), any()) }
    }

    @Test
    fun `UPSERT인데 githubId가 빈 문자열이면 격리한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val payload = UserProfilePayload("UPSERT", 7L, "", Instant.parse("2026-08-26T03:00:00Z"))
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.userProfile, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `지원하지 않는 eventType은 격리한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val payload = UserProfilePayload("UNKNOWN", 7L, "github-user", Instant.parse("2026-08-26T03:00:00Z"))
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.userProfile, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `occurredAt이 없으면 격리한다`() {
        every { processor.processControlRecord(streams.userProfile, any()) } returns false
        val payload = UserProfilePayload("UPSERT", 7L, "github-user", null)
        every { objectMapper.readValue(any<String>(), UserProfilePayload::class.java) } returns payload

        consumer.consume(ConsumerRecord("user.profile.event", 0, 1L, "7", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.userProfile, any(), any()) }
    }
}
