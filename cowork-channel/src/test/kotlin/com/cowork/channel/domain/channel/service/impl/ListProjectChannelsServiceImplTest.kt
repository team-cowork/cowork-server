package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.project.entity.ProjectProjection
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class ListProjectChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val projectProjectionRepository = mockk<ProjectProjectionRepository>()
    private val projectionReadinessGate = mockk<ProjectionReadinessGate>(relaxed = true)
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>()

    private val service =
        ListProjectChannelsServiceImpl(
            channelRepository,
            teamPermission,
            projectProjectionRepository,
            projectionReadinessGate,
            evaluator,
        )

    private fun channel(id: Long = 1L, teamId: Long = 100L) = Channel(
        id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = false, position = 0, createdBy = 1L,
    )

    @Test
    fun `listProjectChannels는 채널이 있으면 팀 멤버 검증 후 반환함`() {
        val ch = channel(id = 1L, teamId = 100L)
        every { projectProjectionRepository.findById(5L) } returns
            Optional.of(ProjectProjection(5L, 100L, sourceOccurredAt = Instant.EPOCH))
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findVisibleByProjectIdOrderByIdAsc(5L, 1L) } returns listOf(ch)
        every { evaluator.filterReadable(100L, 1L, listOf(ch)) } returns listOf(ch)

        val result = service.execute(1L, 5L)
        assertEquals(1, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
        verify { channelRepository.findVisibleByProjectIdOrderByIdAsc(5L, 1L) }
    }

    @Test
    fun `listProjectChannels는 팀 비멤버이면 FORBIDDEN`() {
        every { projectProjectionRepository.findById(5L) } returns
            Optional.of(ProjectProjection(5L, 100L, sourceOccurredAt = Instant.EPOCH))
        every { teamPermission.requireTeamMember(100L, 1L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 5L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `listProjectChannels는 채널이 없으면 빈 리스트를 반환함`() {
        every { projectProjectionRepository.findById(5L) } returns
            Optional.of(ProjectProjection(5L, 100L, sourceOccurredAt = Instant.EPOCH))
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findVisibleByProjectIdOrderByIdAsc(5L, 1L) } returns emptyList()
        every { evaluator.filterReadable(100L, 1L, emptyList()) } returns emptyList()

        val result = service.execute(1L, 5L)
        assertEquals(0, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
    }

    @Test
    fun `listProjectChannels는 프로젝트 projection이 없으면 SERVICE_UNAVAILABLE`() {
        every { projectProjectionRepository.findById(5L) } returns Optional.empty()

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 5L)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        verify(exactly = 0) { teamPermission.requireTeamMember(any(), any()) }
    }
}
