package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjection
import com.cowork.channel.domain.channelRolePolicy.projection.ChannelRolePolicyProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
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

class QueryChannelRolePoliciesServiceImplTest {
    private val channelRepository = mockk<ChannelRepository>()
    private val accessGuard = ChannelAccessGuard(channelRepository)
    private val teamPermissionService = mockk<TeamPermissionService>(relaxed = true)
    private val permissionSupport = mockk<ChannelPermissionSupport>(relaxed = true)
    private val policyRepository = mockk<ChannelRolePolicyProjectionRepository>()
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val readinessGate = mockk<ProjectionReadinessGate>(relaxed = true)
    private val service = QueryChannelRolePoliciesServiceImpl(
        accessGuard,
        teamPermissionService,
        permissionSupport,
        policyRepository,
        roleRepository,
        readinessGate,
    )
    private val occurredAt = Instant.parse("2026-08-30T01:00:00Z")

    @Test
    fun `정책 목록을 role priority 내림차순과 roleId 오름차순으로 반환함`() {
        val channel = channel()
        every { channelRepository.findById(3L) } returns Optional.of(channel)
        every { policyRepository.findAllByTeamIdAndChannelIdAndDeletedFalse(10L, 3L) } returns listOf(
            policy(9L, false),
            policy(2L, true),
            policy(1L, true),
        )
        every { roleRepository.findAllByTeamIdAndRoleIdInAndDeletedFalse(10L, setOf(1L, 2L, 9L)) } returns listOf(
            role(9L, 1),
            role(2L, 100),
            role(1L, 100),
        )

        val result = service.execute(7L, 3L)

        assertEquals(listOf(1L, 2L, 9L), result.map { it.roleId })
        assertEquals(listOf(100, 100, 1), result.map { it.priority })
        verify { teamPermissionService.requireTeamMember(10L, 7L) }
        verify { permissionSupport.requireChannelManager(channel, 7L) }
    }

    @Test
    fun `탈퇴한 channel creator도 정책 목록을 조회할 수 없음`() {
        val channel = channel()
        every { channelRepository.findById(3L) } returns Optional.of(channel)
        every { teamPermissionService.requireTeamMember(10L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 3L)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        verify(exactly = 0) { permissionSupport.requireChannelManager(any(), any()) }
        verify(exactly = 0) { policyRepository.findAllByTeamIdAndChannelIdAndDeletedFalse(any(), any()) }
    }

    private fun channel() = Channel(
        id = 3L,
        teamId = 10L,
        name = "general",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        createdBy = 7L,
    )

    private fun policy(roleId: Long, read: Boolean) = ChannelRolePolicyProjection(
        policyKey = "policy:10:3:$roleId",
        teamId = 10L,
        channelId = 3L,
        roleId = roleId,
        messageRead = read,
        sourceOccurredAt = occurredAt,
    )

    private fun role(roleId: Long, priority: Int) = TeamRoleProjection(
        roleId = roleId,
        teamId = 10L,
        priority = priority,
        sourceOccurredAt = occurredAt,
    )
}
