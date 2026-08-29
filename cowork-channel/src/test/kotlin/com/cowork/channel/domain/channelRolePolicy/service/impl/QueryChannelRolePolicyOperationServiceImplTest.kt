package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperation
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandOperationRepository
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationMapper
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationProjectionFinalizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class QueryChannelRolePolicyOperationServiceImplTest {
    private val operationRepository = mockk<ChannelRolePolicyCommandOperationRepository>()
    private val mapper = mockk<ChannelRolePolicyOperationMapper>()
    private val finalizer = mockk<ChannelRolePolicyOperationProjectionFinalizer>(relaxed = true)
    private val channelRepository = mockk<ChannelRepository>()
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val channelPermissionSupport = mockk<ChannelPermissionSupport>(relaxed = true)
    private val service = QueryChannelRolePolicyOperationServiceImpl(
        operationRepository,
        mapper,
        finalizer,
        channelAccessGuard,
        teamPermissionService,
        channelPermissionSupport,
    )

    @Test
    fun `탈퇴한 creator도 policy operation을 조회할 수 없음`() {
        val operation = ChannelRolePolicyCommandOperation(
            operationId = "019a0000-0000-7000-8000-000000000001",
            idempotencyKey = "key",
            teamId = 10L,
            channelId = 3L,
            roleId = 5L,
            actorId = 7L,
            commandType = ChannelRolePolicyCommandType.UPSERT,
            requestHash = "a".repeat(64),
        )
        val channel = Channel(
            id = 3L,
            teamId = 10L,
            name = "general",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            createdBy = 7L,
        )
        every { operationRepository.findByIdForUpdate(operation.operationId) } returns operation
        every { channelRepository.findById(3L) } returns Optional.of(channel)
        every { teamPermissionService.requireTeamMember(10L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 3L, 5L, operation.operationId)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        verify(exactly = 0) { channelPermissionSupport.requireChannelManager(any(), any()) }
    }
}
