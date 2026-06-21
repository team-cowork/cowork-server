package com.cowork.team.service

import com.cowork.team.domain.TeamInvite
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.CreateInviteRequest
import com.cowork.team.dto.InviteResponse
import com.cowork.team.dto.JoinTeamResponse
import com.cowork.team.dto.TeamEventPayload
import com.cowork.team.event.TeamEventPublisher
import com.cowork.team.repository.TeamInviteRepository
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.security.SecureRandom
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class TeamInviteService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamInviteRepository: TeamInviteRepository,
    private val teamEventPublisher: TeamEventPublisher,
) {
    companion object {
        private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val CODE_LENGTH = 8
        private val random = SecureRandom()
    }

    private fun findTeamOrThrow(teamId: Long) = teamRepository.findById(teamId).orElseThrow {
        ExpectedException("팀을 찾을 수 없습니다. id=$teamId", HttpStatus.NOT_FOUND)
    }

    private fun requireMember(teamId: Long, userId: Long) = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
        ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

    private fun generateUniqueCode(): String {
        repeat(10) {
            val code = String(CharArray(CODE_LENGTH) { CODE_CHARS[random.nextInt(CODE_CHARS.length)] })
            if (!teamInviteRepository.existsByInviteCode(code)) return code
        }
        throw ExpectedException("초대 코드 생성에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @Transactional
    fun createInvite(userId: Long, teamId: Long, request: CreateInviteRequest): InviteResponse {
        requireMember(teamId, userId)
        val team = findTeamOrThrow(teamId)
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

    fun getInvites(userId: Long, teamId: Long): List<InviteResponse> {
        requireMember(teamId, userId)
        return teamInviteRepository.findAllByTeamId(teamId).map { InviteResponse.of(it) }
    }

    @Transactional
    fun deleteInvite(userId: Long, teamId: Long, inviteCode: String) {
        val actor = requireMember(teamId, userId)
        val invite = teamInviteRepository.findByTeamIdAndInviteCode(teamId, inviteCode)
            ?: throw ExpectedException("초대 링크를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        if (invite.isDeleted()) {
            throw ExpectedException("초대 링크를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        val isOwnerOrAdmin = actor.role == TeamRole.OWNER || actor.role == TeamRole.ADMIN
        if (invite.createdBy != userId && !isOwnerOrAdmin) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        invite.softDelete()
    }

    @Transactional
    fun joinTeam(userId: Long, inviteCode: String): JoinTeamResponse {
        val invite = teamInviteRepository.findActiveByInviteCode(inviteCode)
            ?: throw ExpectedException("유효하지 않은 초대 코드입니다.", HttpStatus.NOT_FOUND)

        if (invite.isExpired()) {
            throw ExpectedException("만료된 초대 링크입니다.", HttpStatus.GONE)
        }

        val teamId = invite.team.id
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("이미 팀 멤버입니다.", HttpStatus.CONFLICT)
        }

        val member = teamMemberRepository.save(TeamMember(team = invite.team, userId = userId))

        val payload = TeamEventPayload(
            eventType = "MEMBER_JOINED",
            teamId = teamId,
            teamName = invite.team.name,
            actorUserId = userId,
            targetUserIds = listOf(userId),
        )
        afterCommit { teamEventPublisher.publishLifecycle(payload) }

        return JoinTeamResponse(
            teamId = teamId,
            userId = userId,
            role = member.role.name,
            joinedAt = requireNotNull(member.joinedAt),
        )
    }
}
