package com.cowork.preference

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.service.PreferenceAccessPolicy
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class PreferenceAccessPolicyTest {

    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val projectAccessChecker = mockk<ProjectAccessChecker>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val policy = PreferenceAccessPolicy(teamAccessChecker, projectAccessChecker, channelAccessChecker)

    private fun channel(viewType: ChannelViewType) = Channel(
        teamId = 1, projectId = null, name = "ch", viewType = viewType, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 10,
    )

    @Test
    fun `계정 설정은 누구나 조회할 수 있다`() {
        assertDoesNotThrow { policy.requireReadable(ResourceType.ACCOUNT, 1, 2) }
    }

    @Test
    fun `다른 사용자의 계정 설정은 바꿀 수 없다`() {
        val exception = assertThrows<BusinessException> { policy.requireWritable(ResourceType.ACCOUNT, 1, 2) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `팀 설정 변경에는 팀 관리 권한이 필요하다`() {
        every { teamAccessChecker.requirePermission(1, 2, TeamPermission.MANAGE_TEAM) } returns
            TeamMember(teamId = 1, userId = 2, role = TeamMemberRole.ADMIN)

        policy.requireWritable(ResourceType.TEAM, 1, 2)

        verify { teamAccessChecker.requirePermission(1, 2, TeamPermission.MANAGE_TEAM) }
    }

    @Test
    fun `음성 채널 설정으로 텍스트 채널에 접근하면 400을 반환한다`() {
        every { channelAccessChecker.requireReadable(10, 2) } returns channel(ChannelViewType.TEXT)

        val exception = assertThrows<BusinessException> { policy.requireReadable(ResourceType.VOICE_CHANNEL, 10, 2) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `웹훅 채널은 텍스트 채널 설정으로 다룬다`() {
        every { channelAccessChecker.requireManageable(10, 2) } returns channel(ChannelViewType.WEBHOOK)

        assertDoesNotThrow { policy.requireWritable(ResourceType.TEXT_CHANNEL, 10, 2) }
    }
}
