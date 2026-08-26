package com.cowork.team.domain.team.event

import com.cowork.team.global.outbox.OutboxWriter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TeamEventPublisherTest {

    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val outboxWriter = mockk<OutboxWriter>()
    private val publisher = TeamEventPublisher(entityManager, outboxWriter)

    @Test
    fun `notification trigger는 notification 서비스 계약 형태로 발행한다`() {
        val eventSlot = slot<NotificationTriggerEvent>()
        every { outboxWriter.enqueue("notification.trigger", "7", capture(eventSlot)) } just runs
        val event = NotificationTriggerEvent(
            type = "MEMBER_INVITED",
            targetUserIds = listOf(11L, 12L),
            forcedUserIds = listOf(11L),
            data = mapOf("teamId" to 7L),
        )

        publisher.publishNotification(7L, event)

        assertEquals(event, eventSlot.captured)
        verify(exactly = 1) { outboxWriter.enqueue("notification.trigger", "7", event) }
        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("notification.trigger", "7", event)
        }
    }

    @Test
    fun `일반 lifecycle 이벤트는 Kafka direct send 대신 outbox에 적재한다`() {
        val payload = TeamEventPayload(
            eventType = "TEAM_DELETED",
            teamId = 7L,
            teamName = "Backend",
            actorUserId = 11L,
            targetUserIds = emptyList(),
        )
        every { outboxWriter.enqueue("team.lifecycle", "7", payload) } just runs

        publisher.publishLifecycle(payload)

        verify(exactly = 1) { outboxWriter.enqueue("team.lifecycle", "7", payload) }
        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("team.lifecycle", "7", payload)
        }
    }

    @Test
    fun `team snapshot은 action 이벤트 대신 source version의 TEAM_UPDATED 상태 이벤트를 발행한다`() {
        val payloadSlot = slot<TeamEventPayload>()
        every { outboxWriter.enqueue("team.lifecycle", "7", capture(payloadSlot)) } just runs
        val sourceTime = Instant.parse("2026-08-26T03:00:00.123456Z")

        publisher.publishTeamSnapshot(7L, "Backend", 11L, listOf(11L, 12L), sourceTime)

        assertEquals("TEAM_UPDATED", payloadSlot.captured.eventType)
        assertEquals(sourceTime, payloadSlot.captured.occurredAt)
        assertEquals(true, payloadSlot.captured.snapshot)
        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("team.lifecycle", "7", match<TeamEventPayload> { it.snapshot })
        }
    }
}
