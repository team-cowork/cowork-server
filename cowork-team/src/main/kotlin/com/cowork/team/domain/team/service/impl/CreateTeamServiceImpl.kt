package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamLifecycleSyncPublisher
import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.CreateTeamService
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateTeamServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamLifecycleSyncPublisher: TeamLifecycleSyncPublisher,
) : CreateTeamService {

    @Transactional
    override fun execute(ownerId: Long, request: CreateTeamRequest): TeamResponse {
        val team = teamRepository.save(
            Team(
                name = request.name,
                description = request.description,
                iconUrl = request.iconUrl,
                ownerId = ownerId,
            ),
        )
        val owner = teamMemberRepository.save(
            TeamMember(team = team, userId = ownerId, role = TeamRole.OWNER),
        )

        val payload = TeamEventPayload(
            eventType = "TEAM_CREATED",
            teamId = team.id,
            teamName = team.name,
            actorUserId = ownerId,
            targetUserIds = listOf(ownerId),
        )
        teamEventPublisher.publishLifecycle(payload)
        teamLifecycleSyncPublisher.publishTeamSnapshot(
            actorUserId = ownerId,
            members = listOf(owner),
        )

        return TeamResponse.of(team)
    }
}
