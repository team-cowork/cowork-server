package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.global.outbox.OutboxWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ChannelEventPublisherTest :
    DescribeSpec({
        val entityManager = mockk<EntityManager>(relaxed = true)
        val outboxWriter = mockk<OutboxWriter>()
        val publisher = ChannelEventPublisher(entityManager, outboxWriter)

        describe("publishUpdated 메서드는") {
            it("channelId를 key로 사용하고 projection 필드를 모두 발행한다") {
                val eventSlot = slot<ChannelEvent>()
                every { outboxWriter.enqueue("channel.event", "7", capture(eventSlot)) } just runs
                val channel = Channel(
                    id = 7L,
                    teamId = 3L,
                    projectId = 5L,
                    name = "backend",
                    type = ChannelType.TEXT,
                    viewType = ChannelViewType.TEXT,
                    description = "API",
                    position = 4,
                    createdBy = 11L,
                )

                publisher.publishUpdated(channel)

                eventSlot.captured.channelId shouldBe 7L
                eventSlot.captured.projectId shouldBe 5L
                eventSlot.captured.position shouldBe 4
                verify(exactly = 1) { outboxWriter.enqueue("channel.event", "7", any()) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("channel.event", "7", any())
                }
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각이 아니라 channel source updatedAt을 UTC version으로 사용한다") {
                val eventSlot = slot<ChannelEvent>()
                every { outboxWriter.enqueue("channel.event", "8", capture(eventSlot)) } just runs
                val updatedAt = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
                val channel = Channel(
                    id = 8L,
                    teamId = 3L,
                    name = "snapshot",
                    type = ChannelType.TEXT,
                    viewType = ChannelViewType.TEXT,
                    description = null,
                    createdBy = 11L,
                    updatedAt = updatedAt,
                )

                publisher.publishSnapshot(channel)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe
                    updatedAt.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS)
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("channel.event", "8", any())
                }
            }
        }
    })
