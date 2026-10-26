package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeNicknameRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.ChangeTeamMemberNicknameService
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.ManageRoleContext
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRolePermission
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ChangeTeamMemberNicknameServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamMemberAccessGuard: TeamMemberAccessGuard,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
) : ChangeTeamMemberNicknameService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeNicknameRequest) {
        teamMemberAccessGuard.findTeamForUpdateOrThrow(teamId)
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

    /** OWNER 닉네임은 본인만 바꾸고, 커스텀 역할로 받은 권한은 자신보다 낮은 순위의 MEMBER에게만 쓸 수 있다. */
    private fun requireManageable(teamId: Long, actor: ManageRoleContext, target: TeamMember) {
        if (target.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER의 닉네임은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
        if (actor.member.role == TeamRole.OWNER || actor.member.role == TeamRole.ADMIN) {
            return
        }

        val targetMaxPriority = teamRoleProjectionReader.getMemberRoles(teamId, target.userId)
            .maxOfOrNull { it.priority }
        if (target.role == TeamRole.ADMIN || (targetMaxPriority != null && targetMaxPriority >= actor.maxPriority)) {
            throw ExpectedException("자신보다 높거나 같은 역할의 멤버 닉네임은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    companion object {
        private const val MAX_NICKNAME_LENGTH = 50
    }
}
