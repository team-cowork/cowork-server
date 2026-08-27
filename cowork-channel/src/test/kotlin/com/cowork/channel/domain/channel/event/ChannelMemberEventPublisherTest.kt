package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
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

class ChannelMemberEventPublisherTest :
    DescribeSpec({
        val entityManager = mockk<EntityManager>(relaxed = true)
        val outboxWriter = mockk<OutboxWriter>()
        val publisher = ChannelMemberEventPublisher(entityManager, outboxWriter)

        describe("publishJoin 메서드는") {
            it("channelId와 userId의 복합 key로 멤버 상태를 발행한다") {
                every { outboxWriter.enqueue("channel.member.event", "7:11", any()) } just runs

                publisher.publishJoin(7L, 3L, 11L, "TEXT")

                verify(exactly = 1) { outboxWriter.enqueue("channel.member.event", "7:11", any()) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("channel.member.event", "7:11", any())
                }
            }
        }

        describe("publishSnapshot 메서드는") {
            it("발행 시각이 아니라 membership joinedAt을 UTC version으로 사용한다") {
                val eventSlot = slot<ChannelMemberEvent>()
                every { outboxWriter.enqueue("channel.member.event", "8:11", capture(eventSlot)) } just runs
                val joinedAt = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
                val channel = Channel(
                    id = 8L,
                    teamId = 3L,
                    name = "snapshot",
                    type = ChannelType.TEXT,
                    viewType = ChannelViewType.TEXT,
                    description = null,
                    createdBy = 11L,
                )
                val member = ChannelMember(channelId = 8L, userId = 11L, joinedAt = joinedAt)

                publisher.publishSnapshot(channel, member)

                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe
                    joinedAt.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS)
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("channel.member.event", "8:11", any())
                }
            }
        }
    })
