package com.cowork.team.service

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.event.TeamMemberAddedEvent
import com.cowork.team.event.TeamMemberRemovedEvent
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.user.service.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TeamMemberService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamAccessChecker: TeamAccessChecker,
    private val userService: UserService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun addMember(teamId: Long, actorId: Long, targetUserId: Long): TeamMemberResponse {
        teamAccessChecker.requirePermission(teamId, actorId, TeamPermission.INVITE_MEMBERS)
        if (!userService.exists(targetUserId)) {
            throw BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")
        }
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, targetUserId)) {
            throw BusinessException(HttpStatus.CONFLICT, "이미 팀에 속한 사용자입니다.")
        }
        val team = teamRepository.findByIdOrNull(teamId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다.")
        val member = teamMemberRepository.save(
            TeamMember(teamId = teamId, userId = targetUserId, role = TeamMemberRole.MEMBER),
        )
        eventPublisher.publishEvent(TeamMemberAddedEvent(teamId, team.name, targetUserId, actorId))
        return TeamMemberResponse.of(member, userService.getSummaries(listOf(targetUserId))[targetUserId])
    }

    @Transactional
    fun getMembers(teamId: Long, actorId: Long): List<TeamMemberResponse> {
        teamAccessChecker.requireMember(teamId, actorId)
        val members = teamMemberRepository.findAllByTeamIdOrderByJoinedAtAsc(teamId)
        val users = userService.getSummaries(members.map { it.userId })
        return members.map { TeamMemberResponse.of(it, users[it.userId]) }
    }

    @Transactional
    fun isMember(teamId: Long, userId: Long): Boolean = teamAccessChecker.isMember(teamId, userId)

    @Transactional
    fun changeRole(teamId: Long, actorId: Long, targetUserId: Long, newRole: TeamMemberRole): TeamMemberResponse {
        teamAccessChecker.requireOwner(teamId, actorId)
        if (actorId == targetUserId) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "자신의 역할은 변경할 수 없습니다.")
        }
        val target = findMember(teamId, targetUserId)

        if (newRole == TeamMemberRole.OWNER) {
            val team = teamRepository.findByIdOrNull(teamId)
                ?: throw BusinessException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다.")
            team.transferOwnership(targetUserId)
        }
        target.changeRole(newRole)
        return TeamMemberResponse.of(target, userService.getSummaries(listOf(targetUserId))[targetUserId])
    }

    private fun findMember(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "팀 멤버를 찾을 수 없습니다.")
}
