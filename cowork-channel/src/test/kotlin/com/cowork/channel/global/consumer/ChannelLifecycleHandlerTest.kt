package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelLifecycleHandlerTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>(relaxed = true)
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelMemberEventPublisher = mockk<ChannelMemberEventPublisher>(relaxed = true)

    private val handler =
        ChannelLifecycleHandler(
            channelRepository,
            channelMemberRepository,
            teamMembershipRepository,
            channelEventPublisher,
            channelMemberEventPublisher,
        )

    init {
        every { teamMembershipRepository.save(any()) } answers { firstArg() }
        every { channelEventPublisher.publishDeleted(any(), any()) } answers { secondArg() }
        every { channelMemberEventPublisher.publishLeave(any(), any(), any(), any()) } answers { arg(3) }
    }

    private fun channel(id: Long, teamId: Long, createdBy: Long) = Channel(
        id = id,
        teamId = teamId,
        name = "c$id",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = false,
        createdBy = createdBy,
    )

    @Test
    fun `onMemberUpsert는 신규 멤버 로컬 projection을 저장`() {
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 5L) } returns null
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

        handler.onMemberUpsert(100L, 5L, "MEMBER", occurredAt)

        verify(exactly = 1) {
            teamMembershipRepository.save(
                match<TeamMembership> {
                    it.teamId == 100L &&
                        it.userId == 5L &&
                        it.role == "MEMBER" &&
                        it.active &&
                        it.sourceOccurredAt == occurredAt
                },
            )
        }
    }

    @Test
    fun `onMemberUpsert는 기존 멤버십 role과 version을 업데이트`() {
        val membership = TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER")
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

        handler.onMemberUpsert(100L, 7L, "ADMIN", occurredAt)

        assertEquals("ADMIN", membership.role)
        assertEquals(occurredAt, membership.sourceOccurredAt)
    }

    @Test
    fun `onTeamDeleted는 로컬 멤버십 및 팀의 모든 채널 삭제`() {
        val list = listOf(channel(1L, 100L, 1L), channel(2L, 100L, 2L))
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns list

        handler.onTeamDeleted(100L, Instant.parse("2026-08-26T03:00:00Z"))

        verify(exactly = 1) { channelRepository.deleteAll(list) }
        verify(exactly = 1) { channelEventPublisher.publishDeleted(list[0], any()) }
        verify(exactly = 1) { channelEventPublisher.publishDeleted(list[1], any()) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 로컬 멤버십 삭제, 생성자 채널 삭제, 나머지는 멤버십만 제거`() {
        val ownedByTarget = channel(1L, 100L, 7L)
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns
            listOf(ownedByTarget, channel(2L, 100L, 8L))
        val membership = TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER")
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

        handler.onMemberRemovedFromTeam(100L, 7L, "MEMBER", occurredAt)

        verify(exactly = 1) { teamMembershipRepository.save(membership) }
        assertEquals(false, membership.active)
        assertEquals(occurredAt, membership.sourceOccurredAt)
        verify(exactly = 1) { channelRepository.deleteAll(listOf(ownedByTarget)) }
        verify(exactly = 1) { channelMemberRepository.deleteAllByUserIdAndChannelIdIn(7L, listOf(2L)) }
        verify(exactly = 1) { channelEventPublisher.publishDeleted(ownedByTarget, any()) }
        verify(exactly = 1) { channelMemberEventPublisher.publishLeave(2L, 7L, "MEMBER", any()) }
    }

    @Test
    fun `stale upsert는 최신 delete tombstone을 되살리지 않는다`() {
        val deletedAt = Instant.parse("2026-08-26T03:00:01Z")
        val membership = TeamMembership(
            teamId = 100L,
            userId = 7L,
            role = "MEMBER",
            active = false,
            sourceOccurredAt = deletedAt,
        )
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership

        handler.onMemberUpsert(100L, 7L, "ADMIN", deletedAt.minusSeconds(1))

        verify(exactly = 0) { teamMembershipRepository.save(any()) }
        assertEquals(false, membership.active)
    }

    @Test
    fun `같은 DB microsecond의 upsert는 delete tombstone을 되살리지 않는다`() {
        val deletedAt = Instant.parse("2026-08-26T03:00:01.123456Z")
        val membership = TeamMembership(
            teamId = 100L,
            userId = 7L,
            role = "MEMBER",
            active = false,
            sourceOccurredAt = deletedAt,
        )
        every { teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(100L, 7L) } returns membership

        handler.onMemberUpsert(100L, 7L, "ADMIN", Instant.parse("2026-08-26T03:00:01.123456999Z"))

        verify(exactly = 0) { teamMembershipRepository.save(any()) }
        assertEquals(false, membership.active)
        assertEquals(deletedAt, membership.sourceOccurredAt)
    }
}
