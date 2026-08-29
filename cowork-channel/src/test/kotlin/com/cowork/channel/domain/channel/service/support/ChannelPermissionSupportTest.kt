package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ChannelPermissionSupportTest {
    private val memberRepository = mockk<ChannelMemberRepository>()
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>(relaxed = true)
    private val support = ChannelPermissionSupport(memberRepository, teamPermissionService, evaluator)

    @Test
    fun `비공개 채널은 OWNER라도 구조적 채널 membership이 먼저 필요함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit
        every { memberRepository.existsByChannelIdAndUserId(3L, 7L) } returns false

        val exception = assertThrows(ExpectedException::class.java) {
            support.requireChannelAccess(channel(isPrivate = true), 7L)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        verify(exactly = 0) { evaluator.requireMessageRead(any(), any(), any()) }
    }

    @Test
    fun `공개 채널도 팀 membership 다음 message_read 정책을 검사함`() {
        every { teamPermissionService.requireTeamMember(10L, 7L) } returns Unit

        support.requireChannelAccess(channel(isPrivate = false), 7L)

        verify { evaluator.requireMessageRead(10L, 7L, 3L) }
    }

    private fun channel(isPrivate: Boolean) = Channel(
        id = 3L,
        teamId = 10L,
        name = "general",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = isPrivate,
        createdBy = 1L,
    )
}
