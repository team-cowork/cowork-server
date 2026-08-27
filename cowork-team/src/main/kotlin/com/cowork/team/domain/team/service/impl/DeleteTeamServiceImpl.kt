package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class DeleteTeamServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : DeleteTeamService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER).team
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val payload = TeamEventPayload(
            eventType = "TEAM_DELETED",
            teamId = team.id,
            teamName = team.name,
            actorUserId = userId,
            targetUserIds = emptyList(),
            occurredAt = occurredAt,
        )
        val members = teamMemberRepository.findAllByTeamId(teamId)
        teamRepository.delete(team)

        teamEventPublisher.publishLifecycle(payload)
        members.forEach { teamMemberEventPublisher.publishDelete(it, occurredAt) }
    }
}
