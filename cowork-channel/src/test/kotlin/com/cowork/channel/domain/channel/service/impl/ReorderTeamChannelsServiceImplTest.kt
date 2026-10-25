package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ReorderTeamChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val messageReadPolicyEvaluator = mockk<ChannelMessageReadPolicyEvaluator>()

    private val service = ReorderTeamChannelsServiceImpl(
        channelRepository,
        teamPermission,
        channelEventPublisher,
        messageReadPolicyEvaluator,
    )

    @BeforeEach
    fun allowReorder() {
        every { messageReadPolicyEvaluator.requireReorder(any(), any(), any()) } returns Unit
    }

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
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, second)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, listOf(first, second)) } returns
            listOf(first, second)

        val result = service.execute(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
    }

    @Test
    fun `reorderTeamChannels는 팀 채널 ID 누락 시 BAD_REQUEST`() {
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        val first = channel(id = 1L)
        val second = channel(id = 2L)
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, second)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, listOf(first, second)) } returns
            listOf(first, second)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `reorderTeamChannels는 보이지 않는 채널 ID를 요구하거나 응답하지 않고 기존 위치를 유지함`() {
        val first = channel(id = 1L, position = 0)
        val hidden = channel(id = 2L, isPrivate = true, position = 1)
        val third = channel(id = 3L, position = 2)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, hidden, third)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, listOf(first, hidden, third)) } returns
            listOf(first, third)

        val result = service.execute(7L, 100L, listOf(3L, 1L))

        assertEquals(listOf(3L, 1L), result.map { it.id })
        assertEquals(2, first.position)
        assertEquals(1, hidden.position)
        assertEquals(0, third.position)
    }

    @Test
    fun `권한 없는 채널이 움직이면 전체 요청을 거부하고 위치를 바꾸지 않는다`() {
        val first = channel(id = 1L, position = 0)
        val second = channel(id = 2L, position = 1)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, second)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, any()) } returns listOf(first, second)
        every { messageReadPolicyEvaluator.requireReorder(100L, 7L, any()) } throws
            ExpectedException("채널 재정렬 권한이 없습니다.", HttpStatus.FORBIDDEN)

        assertThrows(ExpectedException::class.java) { service.execute(7L, 100L, listOf(2L, 1L)) }
        assertEquals(0, first.position)
        assertEquals(1, second.position)
    }

    @Test
    fun `위치가 그대로인 채널은 다른 채널의 재정렬을 막지 않는다`() {
        val first = channel(id = 1L, position = 0)
        val fixed = channel(id = 2L, position = 1)
        val third = channel(id = 3L, position = 2)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, fixed, third)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, any()) } returns listOf(first, fixed, third)
        every { messageReadPolicyEvaluator.requireReorder(100L, 7L, any()) } answers {
            if (thirdArg<List<Channel>>().any { it.id == fixed.id }) {
                throw ExpectedException("채널 재정렬 권한이 없습니다.", HttpStatus.FORBIDDEN)
            }
        }
        val result = service.execute(7L, 100L, listOf(3L, 2L, 1L))
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
        assertEquals(1, fixed.position)
    }

    @Test
    fun `이동하는 채널만 음수 임시 position으로 먼저 flush한 뒤 최종 position을 저장함`() {
        val first = channel(id = 1L, position = 0)
        val fixed = channel(id = 2L, position = 1)
        val third = channel(id = 3L, position = 2)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns listOf(first, fixed, third)
        every { messageReadPolicyEvaluator.filterReadable(100L, 7L, any()) } returns listOf(first, fixed, third)
        var positionsAtFlush = emptyList<Int>()
        every { channelRepository.flush() } answers {
            positionsAtFlush = listOf(first.position, fixed.position, third.position)
        }

        service.execute(7L, 100L, listOf(3L, 2L, 1L))

        assertEquals(listOf(-2, 1, -1), positionsAtFlush)
        assertEquals(listOf(2, 1, 0), listOf(first.position, fixed.position, third.position))
        verify(exactly = 1) { channelEventPublisher.publishUpdated(third, any()) }
        verify(exactly = 1) { channelEventPublisher.publishUpdated(first, any()) }
        verify(exactly = 0) { channelEventPublisher.publishUpdated(fixed, any()) }
    }
}
