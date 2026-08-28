package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.ChangeTeamMemberRoleService
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ChangeTeamMemberRoleServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamMemberAccessGuard: TeamMemberAccessGuard,
) : ChangeTeamMemberRoleService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeRoleRequest) {
        teamMemberAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamMemberAccessGuard.requireRole(teamId, actorId, TeamRole.OWNER)

        if (request.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER 역할은 직접 지정할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val targetMember = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
            ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        if (targetMember.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER의 역할은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }

        targetMember.changeRole(request.role)

        teamMemberEventPublisher.publishUpsert(targetMember)
    }
}
