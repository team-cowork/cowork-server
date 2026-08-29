package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandSubmission
import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyAccessSupport
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpsertChannelRolePolicyServiceImplTest {
    private val channelRepository = mockk<ChannelRepository>()
    private val service = UpsertChannelRolePolicyServiceImpl(
        ChannelAccessGuard(channelRepository),
        mockk<ChannelPermissionSupport>(),
        mockk<ChannelRolePolicyAccessSupport>(),
        mockk<ChannelRolePolicyCommandSubmission>(),
    )

    @Test
    fun `빈 permissions를 거부함`() {
        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 3L, 5L, "key", UpsertChannelRolePolicyRequest(emptyMap()))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        verify(exactly = 0) { channelRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `알 수 없는 permission key를 거부함`() {
        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(
                7L,
                3L,
                5L,
                "key",
                UpsertChannelRolePolicyRequest(mapOf("message_read" to true, "message_write" to true)),
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        verify(exactly = 0) { channelRepository.findByIdForUpdate(any()) }
    }
}
