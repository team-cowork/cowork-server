package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.CreateTeamService
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import com.cowork.team.domain.teamMember.TeamMemberships
import com.cowork.team.global.policy.AccountPermissionGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateTeamServiceImpl(
    private val accountPermissionGuard: AccountPermissionGuard,
    private val teamIconOwnershipManager: TeamIconOwnershipManager,
    private val teamRepository: TeamRepository,
    private val teamMemberships: TeamMemberships,
    private val teamEventPublisher: TeamEventPublisher,
) : CreateTeamService {

    @Transactional
    override fun execute(ownerId: Long, request: CreateTeamRequest): TeamResponse {
        accountPermissionGuard.requireCreateTeam(ownerId)
        val team = teamRepository.save(
            Team(
                name = request.name,
                description = request.description,
                iconUrl = null,
                ownerId = ownerId,
            ),
        )
        request.iconUrl?.let { teamIconOwnershipManager.replace(ownerId, team, it) }
        teamEventPublisher.publishCreated(team, ownerId)
        teamMemberships.registerOwner(team.id, ownerId)

        return TeamResponse.of(team)
    }
}
