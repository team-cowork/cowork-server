package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelMemberService
import com.cowork.global.error.BusinessException
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    @Test
    fun `본인은 관리 권한 없이 채널에서 나갈 수 있다`() {
        val member = ChannelMember(channelId = 10, userId = 5)
        every { channelAccessChecker.requireReadable(10, 5) } returns channel
        every { channelMemberRepository.findByChannelIdAndUserId(10, 5) } returns member

        service.removeMember(10, 5, 5)

        verify(exactly = 0) { channelAccessChecker.requireManageable(any(), any()) }
        verify { channelMemberRepository.delete(member) }
    }

    @Test
    fun `다른 멤버를 내보내려면 관리 권한이 필요하다`() {
        every { channelAccessChecker.requireManageable(10, 5) } throws BusinessException(HttpStatus.FORBIDDEN, "권한이 없습니다.")

        assertThrows<BusinessException> { service.removeMember(10, 5, 9) }
        verify(exactly = 0) { channelMemberRepository.delete(any()) }
    }

    @Test
    fun `채널 멤버가 아닌 사용자는 내보낼 수 없다`() {
        every { channelAccessChecker.requireManageable(10, 2) } returns channel
        every { channelMemberRepository.findByChannelIdAndUserId(10, 9) } returns null

        val exception = assertThrows<BusinessException> { service.removeMember(10, 2, 9) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}
