package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.UpdateTeamService
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateTeamServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : UpdateTeamService {

    @Transactional
    override fun execute(userId: Long, teamId: Long, request: UpdateTeamRequest): TeamResponse {
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = teamAccessGuard.findTeamOrThrow(teamId)
        team.update(request.name, request.description, request.iconUrl)
        val members = teamMemberRepository.findAllByTeamId(teamId)
        val payload = TeamEventPayload(
            eventType = "TEAM_UPDATED",
            teamId = team.id,
            teamName = team.name,
            actorUserId = userId,
            targetUserIds = members.map { it.userId },
        )
        teamEventPublisher.publishLifecycle(payload)
        members.forEach(teamMemberEventPublisher::publishUpsert)
        return TeamResponse.of(team)
    }
}
