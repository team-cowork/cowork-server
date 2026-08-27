package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelEventState
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelEventStateRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
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
import java.time.Instant

class ChannelEventPublisherTest :
    DescribeSpec({
        fun channel(name: String = "backend") = Channel(
            id = 7L,
            teamId = 3L,
            projectId = 5L,
            name = name,
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = "API",
            position = 4,
            createdBy = 11L,
        )

        describe("publishUpdated 메서드는") {
            it("channel과 persisted state를 잠그고 clock rollback에도 1 microsecond 큰 version을 저장한다") {
                val channelRepository = mockk<ChannelRepository>()
                val stateRepository = mockk<ChannelEventStateRepository>()
                val entityManager = mockk<EntityManager>(relaxed = true)
                val outboxWriter = mockk<OutboxWriter>()
                val publisher = ChannelEventPublisher(
                    channelRepository,
                    stateRepository,
                    entityManager,
                    outboxWriter,
                )
                val source = channel(name = "new-name")
                val currentVersion = Instant.parse("2026-08-26T03:00:01.123456Z")
                val state = ChannelEventState.create(source, deleted = false, requestedAt = currentVersion)
                val eventSlot = slot<ChannelEvent>()
                every { channelRepository.findByIdForUpdate(7L) } returns source
                every { stateRepository.findByChannelIdForUpdate(7L) } returns state
                every { stateRepository.save(any()) } answers { firstArg() }
                every { outboxWriter.enqueue("channel.event", "7", capture(eventSlot)) } just runs

                val version = publisher.publishUpdated(source, Instant.parse("2026-08-26T02:59:59Z"))

                version shouldBe Instant.parse("2026-08-26T03:00:01.123457Z")
                state.stateOccurredAt shouldBe version
                state.name shouldBe "new-name"
                eventSlot.captured.occurredAt shouldBe version
                eventSlot.captured.snapshot shouldBe false
                verifyOrder {
                    channelRepository.findByIdForUpdate(7L)
                    stateRepository.findByChannelIdForUpdate(7L)
                    stateRepository.save(state)
                    entityManager.flush()
                    outboxWriter.enqueue("channel.event", "7", any())
                }
            }
        }

        describe("publishSnapshot 메서드는") {
            it("삭제 ledger의 event type과 저장 version을 바꾸지 않고 재발행한다") {
                val channelRepository = mockk<ChannelRepository>(relaxed = true)
                val stateRepository = mockk<ChannelEventStateRepository>(relaxed = true)
                val entityManager = mockk<EntityManager>(relaxed = true)
                val outboxWriter = mockk<OutboxWriter>()
                val publisher = ChannelEventPublisher(
                    channelRepository,
                    stateRepository,
                    entityManager,
                    outboxWriter,
                )
                val stateVersion = Instant.parse("2026-08-26T03:00:02.654321Z")
                val state = ChannelEventState.create(channel(), deleted = true, requestedAt = stateVersion)
                val eventSlot = slot<ChannelEvent>()
                every { outboxWriter.enqueue("channel.event", "7", capture(eventSlot)) } just runs

                publisher.publishSnapshot(state)

                eventSlot.captured.eventType shouldBe "DELETED"
                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe stateVersion
                state.stateOccurredAt shouldBe stateVersion
                verify(exactly = 0) { stateRepository.save(any()) }
                verifyOrder {
                    entityManager.flush()
                    outboxWriter.enqueue("channel.event", "7", any())
                }
            }
        }
    })
