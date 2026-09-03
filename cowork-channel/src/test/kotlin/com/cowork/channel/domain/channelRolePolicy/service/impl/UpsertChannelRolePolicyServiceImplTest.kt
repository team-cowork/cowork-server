package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandSubmission
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyOperationStatus
import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyAccessSupport
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpsertChannelRolePolicyServiceImplTest {
    private val channelRepository = mockk<ChannelRepository>()
    private val commandSubmission = mockk<ChannelRolePolicyCommandSubmission>()
    private val service = UpsertChannelRolePolicyServiceImpl(
        ChannelAccessGuard(channelRepository),
        mockk<ChannelPermissionSupport>(),
        mockk<ChannelRolePolicyAccessSupport>(),
        ChannelRolePolicyPermissionSchema(),
        commandSubmission,
    )

    @Test
    fun `누락된 권한은 키별 기본값으로 채우고 알 수 없는 키는 command에서 제거함`() {
        val channel = teamChannel()
        val response = ChannelRolePolicyOperationResponse(
            operationId = "019a0000-0000-7000-8000-000000000001",
            status = ChannelRolePolicyOperationStatus.PENDING,
        )
        every { channelRepository.findByIdForUpdate(3L) } returns channel
        every {
            commandSubmission.submit(
                "key",
                ChannelRolePolicyCommandType.UPSERT,
                10L,
                3L,
                5L,
                7L,
                mapOf("message_read" to false),
                any(),
            )
        } returns response

        val result = service.execute(
            7L,
            3L,
            5L,
            "key",
            UpsertChannelRolePolicyRequest(
                mapOf("future_permission" to mapOf("nested" to true)),
            ),
        )

        assertEquals(response, result)
        verify(exactly = 1) {
            commandSubmission.submit(
                "key",
                ChannelRolePolicyCommandType.UPSERT,
                10L,
                3L,
                5L,
                7L,
                mapOf("message_read" to false),
                any(),
            )
        }
    }

    @Test
    fun `알려진 permission key의 잘못된 타입은 거부함`() {
        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(
                7L,
                3L,
                5L,
                "key",
                UpsertChannelRolePolicyRequest(
                    mapOf("message_read" to "false"),
                ),
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        verify(exactly = 0) { channelRepository.findByIdForUpdate(any()) }
    }

    private fun teamChannel() = Channel(
        id = 3L,
        teamId = 10L,
        name = "general",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        createdBy = 7L,
    )
}
