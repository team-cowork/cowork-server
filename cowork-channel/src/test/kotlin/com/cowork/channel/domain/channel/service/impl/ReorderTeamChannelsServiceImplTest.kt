package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ReorderTeamChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()

    private val service = ReorderTeamChannelsServiceImpl(channelRepository, teamPermission)

    private fun channel(
        id: Long = 1L,
        teamId: Long = 100L,
        type: ChannelType = ChannelType.TEXT,
        view: ChannelViewType = ChannelViewType.TEXT,
        createdBy: Long = 1L,
        isPrivate: Boolean = false,
        position: Int = 0,
        projectId: Long? = null,
    ) = Channel(
        id = id, teamId = teamId, name = "ch", type = type, viewType = view,
        description = null, isPrivate = isPrivate, position = position, createdBy = createdBy, projectId = projectId,
    )

    @Test
    fun `reorderTeamChannels는 요청 순서대로 position을 갱신함`() {
        val first = channel(id = 1L, position = 0)
        val second = channel(id = 2L, position = 1)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(first, second)

        val result = service.execute(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
    }

    @Test
    fun `reorderTeamChannels는 팀 채널 ID 누락 시 BAD_REQUEST`() {
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns
            listOf(channel(id = 1L), channel(id = 2L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
