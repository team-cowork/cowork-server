package com.cowork.preference

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.service.PreferenceAccessPolicy
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.service.TeamAccessChecker
import io.mockk.mockk
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
}
