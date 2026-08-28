package com.cowork.team.domain.team.service.impl

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
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        team.update(request.name, request.description, request.iconUrl)
        val members = teamMemberRepository.findAllByTeamIdForUpdate(teamId)
        teamEventPublisher.publishUpdated(team, userId)
        members.forEach(teamMemberEventPublisher::publishUpsert)
        return TeamResponse.of(team)
    }
}
