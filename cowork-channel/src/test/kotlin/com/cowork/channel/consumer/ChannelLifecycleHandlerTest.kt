package com.cowork.channel.consumer

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.TeamMembership
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.TeamMembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelLifecycleHandlerTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>(relaxed = true)

    private val handler = ChannelLifecycleHandler(channelRepository, channelMemberRepository, teamMembershipRepository)

    private fun channel(id: Long, teamId: Long, createdBy: Long) = Channel(
        id = id, teamId = teamId, name = "c$id", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = false, createdBy = createdBy,
    )

    @Test
    fun `onMemberInvited는 신규 멤버 로컬 멤버십 저장`() {
        every { teamMembershipRepository.findAllByTeamIdAndUserIdIn(100L, listOf(5L)) } returns emptyList()

        handler.onMemberInvited(100L, listOf(5L), "MEMBER")

        verify(exactly = 1) { teamMembershipRepository.saveAll(match<List<TeamMembership>> { it.size == 1 && it[0].teamId == 100L && it[0].userId == 5L && it[0].role == "MEMBER" }) }
    }

    @Test
    fun `onRoleChanged는 기존 멤버십 role 업데이트`() {
        val membership = TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER")
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership

        handler.onRoleChanged(100L, 7L, "ADMIN")

        assertEquals("ADMIN", membership.role)
    }

    @Test
    fun `onTeamDeleted는 로컬 멤버십 및 팀의 모든 채널 삭제`() {
        val list = listOf(channel(1L, 100L, 1L), channel(2L, 100L, 2L))
        every { channelRepository.findAllByTeamIdOrderByIdAsc(100L) } returns list

        handler.onTeamDeleted(100L)

        verify(exactly = 1) { teamMembershipRepository.deleteAllByTeamId(100L) }
        verify(exactly = 1) { channelRepository.deleteAll(list) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 로컬 멤버십 삭제, 생성자 채널 삭제, 나머지는 멤버십만 제거`() {
        val ownedByTarget = channel(1L, 100L, 7L)
        every { channelRepository.findAllByTeamIdAndCreatedByOrderByIdAsc(100L, 7L) } returns listOf(ownedByTarget)
        every { channelRepository.findIdsByTeamIdAndCreatedByNot(100L, 7L) } returns listOf(2L)

        handler.onMemberRemovedFromTeam(100L, 7L)

        verify(exactly = 1) { teamMembershipRepository.deleteByTeamIdAndUserId(100L, 7L) }
        verify(exactly = 1) { channelRepository.deleteAll(listOf(ownedByTarget)) }
        verify(exactly = 1) { channelMemberRepository.deleteAllByUserIdAndChannelIdIn(7L, listOf(2L)) }
    }
}
