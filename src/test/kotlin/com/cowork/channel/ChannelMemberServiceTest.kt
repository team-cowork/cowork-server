package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelMemberService
import com.cowork.global.error.BusinessException
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class ChannelMemberServiceTest {

    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val userService = mockk<UserService>(relaxed = true)
    private val service = ChannelMemberService(channelMemberRepository, channelAccessChecker, teamAccessChecker, userService)

    private val channel = Channel(
        teamId = 1, projectId = null, name = "기획", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = true, position = 0, createdBy = 2, id = 10,
    )

    @Test
    fun `팀 멤버가 아니면 채널에 추가할 수 없다`() {
        every { channelAccessChecker.requireManageable(10, 2) } returns channel
        every { teamAccessChecker.isMember(1, 9) } returns false

        val exception = assertThrows<BusinessException> { service.addMember(10, 2, 9) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `이미 참여한 멤버는 다시 추가할 수 없다`() {
        every { channelAccessChecker.requireManageable(10, 2) } returns channel
        every { teamAccessChecker.isMember(1, 9) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(10, 9) } returns true

        val exception = assertThrows<BusinessException> { service.addMember(10, 2, 9) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }
}
