package com.cowork.team.event

import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.repository.TeamMemberRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class TeamLifecycleSyncPublisher(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
) {

    fun publishTeamSnapshot(actorUserId: Long, members: List<TeamMember>) {
        if (members.isEmpty()) return

        val team = members.first().team
        teamEventPublisher.publishMemberInvited(
            teamId = team.id,
            teamName = team.name,
            actorUserId = actorUserId,
            targetUserIds = members.map(TeamMember::userId),
        )

        members.groupBy(TeamMember::role)
            .filterKeys { it != TeamRole.MEMBER }
            .forEach { (role, groupedMembers) ->
                teamEventPublisher.publishRoleChanged(
                    teamId = team.id,
                    teamName = team.name,
                    actorUserId = actorUserId,
                    targetUserIds = groupedMembers.map(TeamMember::userId),
                    newRole = role.name,
                )
            }
    }

    @EventListener(ApplicationReadyEvent::class)
    @Transactional(readOnly = true)
    fun publishAllSnapshots() {
        var page = 0
        do {
            val idSlice = teamMemberRepository.findAllIds(PageRequest.of(page++, 500))
            teamMemberRepository.findAllWithTeamByIds(idSlice.content)
                .groupBy { it.team.id }
                .values
                .forEach { members ->
                    val actorUserId = members.firstOrNull { it.role == TeamRole.OWNER }?.userId ?: members.first().userId
                    publishTeamSnapshot(actorUserId = actorUserId, members = members)
                }
        } while (idSlice.hasNext())
    }
}
