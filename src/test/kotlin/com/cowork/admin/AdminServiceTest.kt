package com.cowork.admin

import com.cowork.admin.service.AdminService
import com.cowork.channel.repository.ChannelRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.user.domain.User
import com.cowork.user.domain.UserRole
import com.cowork.user.repository.UserRepository
import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.repository.VoiceSessionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.util.Optional

class AdminServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val teamRepository = mockk<TeamRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val channelRepository = mockk<ChannelRepository>()
    private val messageRepository = mockk<MessageRepository>()
    private val voiceSessionRepository = mockk<VoiceSessionRepository>()
    private val service = AdminService(
        userRepository, teamRepository, projectRepository, channelRepository, messageRepository, voiceSessionRepository,
    )

    private val admin = AuthUser(1, UserRole.ADMIN)
    private val member = AuthUser(2, UserRole.MEMBER)

    @Test
    fun `운영자가 아니면 통계를 조회할 수 없다`() {
        val exception = assertThrows<BusinessException> { service.getStats(member) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `도메인별 개수와 진행 중인 음성 세션 수를 모은다`() {
        every { userRepository.count() } returns 120
        every { teamRepository.count() } returns 14
        every { projectRepository.count() } returns 31
        every { channelRepository.count() } returns 88
        every { messageRepository.count() } returns 5400
        every { voiceSessionRepository.countByStatus(VoiceSessionStatus.ACTIVE) } returns 2

        val stats = service.getStats(admin)

        assertEquals(120, stats.users)
        assertEquals(2, stats.activeVoiceSessions)
    }

    @Test
    fun `자기 자신의 권한은 바꿀 수 없다`() {
        assertThrows<BusinessException> { service.changeUserRole(admin, 1, UserRole.MEMBER) }
    }

    @Test
    fun `다른 사용자를 운영자로 지정한다`() {
        val user = User(dataGsmId = 10, name = "홍길동", email = "a@gsm.hs.kr", sex = "MAN", id = 2)
        every { userRepository.findById(2) } returns Optional.of(user)

        service.changeUserRole(admin, 2, UserRole.ADMIN)

        assertEquals(UserRole.ADMIN, user.role)
    }
}
