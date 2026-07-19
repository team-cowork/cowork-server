package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.global.client.ProjectClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ListProjectChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val projectClient = mockk<ProjectClient>()

    private val service = ListProjectChannelsServiceImpl(channelRepository, teamPermission, projectClient)

    private fun channel(id: Long = 1L, teamId: Long = 100L) = Channel(
        id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = false, position = 0, createdBy = 1L,
    )

    @Test
    fun `listProjectChannels는 채널이 있으면 팀 멤버 검증 후 반환함`() {
        val ch = channel(id = 1L, teamId = 100L)
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findAllByProjectIdOrderByIdAsc(5L) } returns listOf(ch)

        val result = service.execute(1L, 5L)
        assertEquals(1, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
    }

    @Test
    fun `listProjectChannels는 팀 비멤버이면 FORBIDDEN`() {
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 5L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `listProjectChannels는 채널이 없으면 빈 리스트를 반환함`() {
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findAllByProjectIdOrderByIdAsc(5L) } returns emptyList()

        val result = service.execute(1L, 5L)
        assertEquals(0, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
    }
}
