package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeNicknameRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.UpdateTeamMemberNicknameService
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.ManageRoleContext
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRolePermission
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UpdateTeamMemberNicknameServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
) : UpdateTeamMemberNicknameService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeNicknameRequest) {
        teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        val nickname = normalize(request.nickname)

        val targetMember = if (actorId == targetUserId) {
            teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, actorId)
                ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        } else {
            val actor = teamRoleAccessGuard.requirePermission(
                teamId,
                actorId,
                TeamRolePermission.MANAGE_NICKNAMES.key,
                "닉네임 관리 권한이 없습니다.",
            )
            val target = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
                ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
            requireManageable(teamId, actor, target)
            target
        }

        targetMember.changeNickname(nickname)

        teamMemberEventPublisher.publishUpsert(targetMember)
    }

    private fun normalize(nickname: String?): String? {
        val trimmed = nickname?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.codePointCount(0, trimmed.length) > MAX_NICKNAME_LENGTH) {
            throw ExpectedException("닉네임은 50자 이하여야 합니다.", HttpStatus.BAD_REQUEST)
        }
        return trimmed
    }

    /** 소유자를 보호하며 역할 우선순위에 따라 대상 멤버를 판정한다. */
    private fun requireManageable(teamId: Long, actor: ManageRoleContext, target: TeamMember) {
        if (target.team.ownerId == target.userId) {
            throw ExpectedException("OWNER의 닉네임은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
        val targetMaxPriority = teamRoleProjectionReader.getMemberRoles(teamId, target.userId)
            .maxOfOrNull { it.priority }
        if (targetMaxPriority != null && targetMaxPriority >= actor.maxPriority) {
            throw ExpectedException("자신보다 높거나 같은 역할의 멤버 닉네임은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    companion object {
        private const val MAX_NICKNAME_LENGTH = 50
    }
}
