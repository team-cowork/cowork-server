package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SearchArchivedChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>()
    private val teamPermission = mockk<TeamPermissionService>()
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>()

    private val service = SearchArchivedChannelsServiceImpl(channelRepository, teamPermission, evaluator)

    private fun archived(id: Long) = Channel(
        id = id,
        teamId = 10L,
        projectId = 5L,
        name = "release-$id",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        createdBy = 1L,
    ).apply { archive(1L, LocalDateTime.of(2026, 11, 10, 9, 0)) }

    @Test
    fun `조건으로 찾은 보관 채널 중 읽을 수 있는 채널만 반환한다`() {
        val readable = archived(1L)
        val hidden = archived(2L)
        every { teamPermission.requireTeamMember(10L, 7L) } just runs
        every { channelRepository.searchArchived(10L, null, 5L, "release") } returns listOf(readable, hidden)
        every { evaluator.filterReadable(10L, 7L, listOf(readable, hidden)) } returns listOf(readable)

        val result = service.execute(7L, 10L, null, 5L, " release ")

        assertEquals(listOf(1L), result.map { it.id })
        assertEquals(true, result.single().archived)
    }

    @Test
    fun `빈 이름 검색어는 이름 조건 없이 조회한다`() {
        every { teamPermission.requireTeamMember(10L, 7L) } just runs
        every { channelRepository.searchArchived(10L, 1L, null, null) } returns emptyList()
        every { evaluator.filterReadable(10L, 7L, emptyList()) } returns emptyList()

        service.execute(7L, 10L, 1L, null, "  ")

        verify { channelRepository.searchArchived(10L, 1L, null, null) }
    }
}
