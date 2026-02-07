package com.cowork.admin.service

import com.cowork.admin.dto.AdminStatsResponse
import com.cowork.channel.repository.ChannelRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.user.repository.UserRepository
import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.repository.VoiceSessionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 운영자가 서비스 상태를 확인하고 사용자 권한을 관리할 때 쓴다.
 */
@Service
class AdminService(
    private val userRepository: UserRepository,
    private val teamRepository: TeamRepository,
    private val projectRepository: ProjectRepository,
    private val channelRepository: ChannelRepository,
    private val messageRepository: MessageRepository,
    private val voiceSessionRepository: VoiceSessionRepository,
) {

    @Transactional(readOnly = true)
    fun getStats(actor: AuthUser): AdminStatsResponse {
        requireAdmin(actor)
        return AdminStatsResponse(
            users = userRepository.count(),
            teams = teamRepository.count(),
            projects = projectRepository.count(),
            channels = channelRepository.count(),
            messages = messageRepository.count(),
            activeVoiceSessions = voiceSessionRepository.countByStatus(VoiceSessionStatus.ACTIVE),
        )
    }

    private fun requireAdmin(actor: AuthUser) {
        if (!actor.isAdmin) {
            throw BusinessException(HttpStatus.FORBIDDEN, "운영자만 사용할 수 있습니다.")
        }
    }
}
