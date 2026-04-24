package com.cowork.team.service

import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.ChangeRoleRequest
import com.cowork.team.dto.InviteMembersRequest
import com.cowork.team.dto.TeamEventPayload
import com.cowork.team.dto.TeamMemberResponse
import com.cowork.team.event.TeamEventPublisher
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class TeamMemberService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
) {

    private fun findTeamOrThrow(teamId: Long) =
        teamRepository.findById(teamId).orElseThrow {
            ExpectedException("팀을 찾을 수 없습니다. id=$teamId", HttpStatus.NOT_FOUND)
        }

    private fun requireRole(teamId: Long, userId: Long, vararg roles: TeamRole): TeamMember =
        teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, userId, roles.toList())
            ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

    @Transactional
    fun inviteMembers(actorId: Long, teamId: Long, request: InviteMembersRequest): List<TeamMemberResponse> {
        requireRole(teamId, actorId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = findTeamOrThrow(teamId)

        val existingUserIds = teamMemberRepository.findAllByTeamId(teamId).map { it.userId }.toSet()
        val newMembers = request.userIds
            .filter { it !in existingUserIds }
            .map { userId -> TeamMember(team = team, userId = userId, role = TeamRole.MEMBER) }

        val savedMembers = teamMemberRepository.saveAll(newMembers)

        if (savedMembers.isNotEmpty()) {
            val payload = TeamEventPayload(
                eventType = "MEMBER_INVITED",
                teamId = teamId,
                teamName = team.name,
                actorUserId = actorId,
                targetUserIds = savedMembers.map { it.userId },
            )
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = teamEventPublisher.publish(payload)
            })
        }

        return savedMembers.map { TeamMemberResponse.of(it) }
    }

    fun getMembers(teamId: Long): List<TeamMemberResponse> =
        teamMemberRepository.findAllByTeamId(teamId).map { TeamMemberResponse.of(it) }

    fun isMember(teamId: Long, userId: Long): Boolean =
        teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)

    @Transactional
    fun changeRole(actorId: Long, teamId: Long, targetUserId: Long, request: ChangeRoleRequest) {
        requireRole(teamId, actorId, TeamRole.OWNER)

        if (request.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER 역할은 직접 지정할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val targetMember = teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId)
            ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        if (targetMember.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER의 역할은 변경할 수 없습니다.", HttpStatus.FORBIDDEN)
        }

        targetMember.changeRole(request.role)
    }

    @Transactional
    fun removeMember(actorId: Long, teamId: Long, targetUserId: Long) {
        val team = findTeamOrThrow(teamId)
        val actorMember = teamMemberRepository.findByTeamIdAndUserId(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

        val isSelf = actorId == targetUserId
        val canManage = actorMember.role == TeamRole.OWNER || actorMember.role == TeamRole.ADMIN

        if (!isSelf && !canManage) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        val targetMember = if (isSelf) actorMember else teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId)
            ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        if (targetMember.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER는 팀을 탈퇴하거나 제거할 수 없습니다.", HttpStatus.FORBIDDEN)
        }

        teamMemberRepository.delete(targetMember)

        val payload = TeamEventPayload(
            eventType = "MEMBER_REMOVED",
            teamId = teamId,
            teamName = team.name,
            actorUserId = actorId,
            targetUserIds = listOf(targetUserId),
        )
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = teamEventPublisher.publish(payload)
        })
    }
}
