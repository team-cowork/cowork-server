package com.cowork.team.domain.teamMember

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

@Service
class TeamMemberships(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun registerOwner(teamId: Long, ownerId: Long) {
        val owner = teamMemberRepository.save(TeamMember(teamId = teamId, userId = ownerId, role = TeamRole.OWNER))
        teamMemberEventPublisher.publishUpsert(owner)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun transferOwnership(teamId: Long, actorId: Long, targetId: Long, updateTeamOwner: () -> Unit) {
        val actor = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        val target = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetId)
            ?: throw ExpectedException("소유권을 받을 팀 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        actor.changeRole(TeamRole.MEMBER)
        target.changeRole(TeamRole.OWNER)
        // Record the team ownership change before the two membership events in the same outbox.
        updateTeamOwner()
        teamMemberEventPublisher.publishUpsert(actor)
        teamMemberEventPublisher.publishUpsert(target)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishTeamUpdate(teamId: Long) {
        teamMemberRepository.findAllByTeamIdForUpdate(teamId).forEach(teamMemberEventPublisher::publishUpsert)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun removeAll(teamId: Long, occurredAt: Instant) {
        val members = teamMemberRepository.findAllByTeamIdForUpdate(teamId)
        members.forEach { teamMemberEventPublisher.publishDelete(it, occurredAt) }
        teamMemberRepository.deleteAll(members)
    }

    @Transactional(readOnly = true)
    fun rolesByTeam(userId: Long): Map<Long, String> =
        teamMemberRepository.findAllByUserId(userId).associate { it.teamId to it.role.name }
}
