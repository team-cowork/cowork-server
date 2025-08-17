package com.cowork.team.service

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.StorageService
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.UpdateTeamRequest
import com.cowork.team.event.TeamDeletedEvent
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamAccessChecker: TeamAccessChecker,
    private val storageService: StorageService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(userId: Long, request: CreateTeamRequest): TeamResponse {
        val team = teamRepository.save(
            Team(
                name = request.name,
                description = request.description,
                visibility = request.visibility,
                ownerId = userId,
            ),
        )
        teamMemberRepository.save(TeamMember(teamId = team.id, userId = userId, role = TeamMemberRole.OWNER))
        return toResponse(team, TeamMemberRole.OWNER)
    }

    @Transactional
    fun getMyTeams(userId: Long): List<TeamResponse> =
        teamRepository.findAllByMemberUserId(userId).map { team ->
            toResponse(team, teamMemberRepository.findByTeamIdAndUserId(team.id, userId)?.role)
        }

    private fun toResponse(team: Team, myRole: TeamMemberRole?) = TeamResponse.of(
        team = team,
        iconUrl = team.iconKey?.let(storageService::createDownloadUrl),
        memberCount = teamMemberRepository.countByTeamId(team.id),
        myRole = myRole,
    )
}
