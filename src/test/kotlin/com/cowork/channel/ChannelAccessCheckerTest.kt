package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ChannelAccessCheckerTest {

    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val checker = ChannelAccessChecker(mockk<ChannelRepository>(), channelMemberRepository, teamAccessChecker)

    private fun channel(isPrivate: Boolean) = Channel(
        teamId = 1,
        projectId = null,
        name = "general",
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = isPrivate,
        position = 0,
        createdBy = 1,
        id = 7,
    )

    private fun teamMember(userId: Long, role: TeamMemberRole = TeamMemberRole.MEMBER) =
        TeamMember(teamId = 1, userId = userId, role = role)

    @Test
    fun `공개 채널은 팀 멤버라면 읽을 수 있다`() {
        every { teamAccessChecker.requireMember(1, 2) } returns teamMember(2)

        assertTrue(checker.canRead(channel(isPrivate = false), 2))
    }

    @Test
    fun `팀 멤버가 아니면 공개 채널도 읽을 수 없다`() {
        every { teamAccessChecker.requireMember(1, 3) } throws BusinessException(HttpStatus.FORBIDDEN, "팀 멤버가 아닙니다.")

        assertFalse(checker.canRead(channel(isPrivate = false), 3))
    }

    @Test
    fun `비공개 채널은 채널 멤버이거나 채널 관리 권한이 있어야 읽을 수 있다`() {
        val outsider = teamMember(4)
        val manager = teamMember(5, TeamMemberRole.ADMIN)
        every { teamAccessChecker.requireMember(1, 4) } returns outsider
        every { teamAccessChecker.requireMember(1, 5) } returns manager
        every { teamAccessChecker.requireMember(1, 6) } returns teamMember(6)
        every { channelMemberRepository.existsByChannelIdAndUserId(7, 4) } returns false
        every { channelMemberRepository.existsByChannelIdAndUserId(7, 5) } returns false
        every { channelMemberRepository.existsByChannelIdAndUserId(7, 6) } returns true
        every { teamAccessChecker.hasPermission(outsider, TeamPermission.MANAGE_CHANNELS) } returns false
        every { teamAccessChecker.hasPermission(manager, TeamPermission.MANAGE_CHANNELS) } returns true

        val privateChannel = channel(isPrivate = true)

        assertFalse(checker.canRead(privateChannel, 4))
        assertTrue(checker.canRead(privateChannel, 5))
        assertTrue(checker.canRead(privateChannel, 6))
    }
}
