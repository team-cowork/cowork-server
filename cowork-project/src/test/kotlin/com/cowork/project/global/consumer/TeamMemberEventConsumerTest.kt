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

class TeamMemberEventConsumerTest {

    private val handler = mockk<ProjectLifecycleHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = TeamMemberEventConsumer(handler, objectMapper, processor, streams)

    @Test
    fun `복합 key와 occurredAt이 유효한 member event만 반영한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = TeamMemberEventPayload("UPSERT", 100L, 7L, "ADMIN", occurredAt)
        every { objectMapper.readValue(any<String>(), TeamMemberEventPayload::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("team.member.event", 0, 1L, "100:7", "{}"))
        consumer.consume(ConsumerRecord("team.member.event", 0, 2L, "100:8", "{}"))

        verify(exactly = 1) { handler.onMemberUpsert(100L, 7L, "ADMIN", occurredAt) }
    }
}
