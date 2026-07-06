package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.presentation.data.request.CreateInviteRequest
import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.CreateInviteService
import com.cowork.team.domain.teamInvite.service.TeamInviteAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.security.SecureRandom
import java.time.LocalDateTime

@Service
@Transactional
class CreateInviteServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val teamInviteAccessGuard: TeamInviteAccessGuard,
) : CreateInviteService {

    companion object {
        private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val CODE_LENGTH = 8
        private val random = SecureRandom()
    }

    private fun generateUniqueCode(): String {
        repeat(10) {
            val code = String(CharArray(CODE_LENGTH) { CODE_CHARS[random.nextInt(CODE_CHARS.length)] })
            if (!teamInviteRepository.existsByInviteCode(code)) return code
        }
        throw ExpectedException("초대 코드 생성에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR)
    }

    override fun execute(userId: Long, teamId: Long, request: CreateInviteRequest): InviteResponse {
        teamInviteAccessGuard.requireMember(teamId, userId)
        val team = teamInviteAccessGuard.findTeamOrThrow(teamId)
        val expiresAt = request.duration.toExpiresAt(LocalDateTime.now())
        val invite = TeamInvite(
            team = team,
            inviteCode = generateUniqueCode(),
            createdBy = userId,
            duration = request.duration.value,
            expiresAt = expiresAt,
        )
        return InviteResponse.of(teamInviteRepository.save(invite))
    }
}
