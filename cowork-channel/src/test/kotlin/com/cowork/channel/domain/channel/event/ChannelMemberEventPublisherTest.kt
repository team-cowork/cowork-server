package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelMemberEventState
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelMemberEventStateRepository
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
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

class ChannelMemberEventPublisherTest :
    DescribeSpec({
        fun channel() = Channel(
            id = 7L,
            teamId = 3L,
            name = "backend",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            createdBy = 11L,
        )

        fun publisherFixture(): PublisherFixture {
            val channelRepository = mockk<ChannelRepository>()
            val memberRepository = mockk<ChannelMemberRepository>()
            val stateRepository = mockk<ChannelMemberEventStateRepository>()
            val entityManager = mockk<EntityManager>(relaxed = true)
            val outboxWriter = mockk<OutboxWriter>()
            return PublisherFixture(
                ChannelMemberEventPublisher(
                    channelRepository,
                    memberRepository,
                    stateRepository,
                    entityManager,
                    outboxWriter,
                ),
                channelRepository,
                memberRepository,
                stateRepository,
                entityManager,
                outboxWriter,
            )
        }

        describe("publishJoin 메서드는") {
            it("관계와 tombstone ledger를 잠그고 재가입을 더 큰 version으로 저장한다") {
                val fixture = publisherFixture()
                val source = channel()
                val member = ChannelMember(channelId = 7L, userId = 11L)
                val deletedAt = Instant.parse("2026-08-26T03:00:01.123456Z")
                val state = ChannelMemberEventState.create(
                    source,
                    userId = 11L,
                    role = "MEMBER",
                    deleted = true,
                    requestedAt = deletedAt,
                )
                val eventSlot = slot<ChannelMemberEvent>()
                every { fixture.channelRepository.findByIdForUpdate(7L) } returns source
                every { fixture.memberRepository.findByChannelIdAndUserIdForUpdate(7L, 11L) } returns member
                every { fixture.stateRepository.findByKeyForUpdate(7L, 11L) } returns state
                every { fixture.stateRepository.save(any()) } answers { firstArg() }
                every {
                    fixture.outboxWriter.enqueue("channel.member.event", "7:11", capture(eventSlot))
                } just runs

                val version = fixture.publisher.publishJoin(
                    7L,
                    11L,
                    requestedAt = Instant.parse("2026-08-26T03:00:00Z"),
                )

                version shouldBe Instant.parse("2026-08-26T03:00:01.123457Z")
                state.deleted shouldBe false
                eventSlot.captured.eventType shouldBe ChannelMemberEventType.JOIN
                eventSlot.captured.occurredAt shouldBe version
                verifyOrder {
                    fixture.channelRepository.findByIdForUpdate(7L)
                    fixture.memberRepository.findByChannelIdAndUserIdForUpdate(7L, 11L)
                    fixture.stateRepository.findByKeyForUpdate(7L, 11L)
                    fixture.stateRepository.save(state)
                    fixture.entityManager.flush()
                    fixture.outboxWriter.enqueue("channel.member.event", "7:11", any())
                }
            }
        }

        describe("publishLeave 메서드는") {
            it("관계 row가 이미 없어도 잠금 조회 후 durable tombstone을 만든다") {
                val fixture = publisherFixture()
                val source = channel()
                val requestedAt = Instant.parse("2026-08-26T03:00:01.123456999Z")
                val eventSlot = slot<ChannelMemberEvent>()
                every { fixture.channelRepository.findByIdForUpdate(7L) } returns source
                every { fixture.memberRepository.findByChannelIdAndUserIdForUpdate(7L, 11L) } returns null
                every { fixture.stateRepository.findByKeyForUpdate(7L, 11L) } returns null
                every { fixture.stateRepository.save(any()) } answers { firstArg() }
                every {
                    fixture.outboxWriter.enqueue("channel.member.event", "7:11", capture(eventSlot))
                } just runs

                val version = fixture.publisher.publishLeave(7L, 11L, requestedAt = requestedAt)

                version shouldBe Instant.parse("2026-08-26T03:00:01.123456Z")
                eventSlot.captured.eventType shouldBe ChannelMemberEventType.LEAVE
                eventSlot.captured.snapshot shouldBe false
                eventSlot.captured.occurredAt shouldBe version
            }
        }

        describe("publishSnapshot 메서드는") {
            it("삭제 ledger의 저장 version을 바꾸지 않고 LEAVE snapshot으로 재발행한다") {
                val fixture = publisherFixture()
                val stateVersion = Instant.parse("2026-08-26T03:00:02.654321Z")
                val state = ChannelMemberEventState.create(
                    channel(),
                    userId = 11L,
                    role = "MEMBER",
                    deleted = true,
                    requestedAt = stateVersion,
                )
                val eventSlot = slot<ChannelMemberEvent>()
                every {
                    fixture.outboxWriter.enqueue("channel.member.event", "7:11", capture(eventSlot))
                } just runs

                fixture.publisher.publishSnapshot(state)

                eventSlot.captured.eventType shouldBe ChannelMemberEventType.LEAVE
                eventSlot.captured.snapshot shouldBe true
                eventSlot.captured.occurredAt shouldBe stateVersion
                verify(exactly = 0) { fixture.stateRepository.save(any()) }
            }
        }
    })

private data class PublisherFixture(
    val publisher: ChannelMemberEventPublisher,
    val channelRepository: ChannelRepository,
    val memberRepository: ChannelMemberRepository,
    val stateRepository: ChannelMemberEventStateRepository,
    val entityManager: EntityManager,
    val outboxWriter: OutboxWriter,
)
