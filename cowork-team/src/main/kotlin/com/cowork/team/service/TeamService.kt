package com.cowork.team.service

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.CreateTeamRequest
import com.cowork.team.dto.TeamEventPayload
import com.cowork.team.dto.TeamResponse
import com.cowork.team.dto.TeamSummaryResponse
import com.cowork.team.dto.UpdateTeamRequest
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
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
) {

    private fun findTeamOrThrow(teamId: Long): Team =
        teamRepository.findById(teamId).orElseThrow {
            ExpectedException("팀을 찾을 수 없습니다. id=$teamId", HttpStatus.NOT_FOUND)
        }

    private fun requireRole(teamId: Long, userId: Long, vararg roles: TeamRole): TeamMember =
        teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, userId, roles.toList())
            ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

    @Transactional
    fun createTeam(ownerId: Long, request: CreateTeamRequest): TeamResponse {
        val team = teamRepository.save(
            Team(
                name = request.name,
                description = request.description,
                iconUrl = request.iconUrl,
                ownerId = ownerId,
            )
        )
        teamMemberRepository.save(
            TeamMember(team = team, userId = ownerId, role = TeamRole.OWNER)
        )

        val payload = TeamEventPayload(
            eventType = "TEAM_CREATED",
            teamId = team.id,
            teamName = team.name,
            actorUserId = ownerId,
            targetUserIds = listOf(ownerId),
        )
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = teamEventPublisher.publish(payload)
        })

        return TeamResponse.of(team)
    }

    fun getMyTeams(userId: Long): List<TeamSummaryResponse> =
        teamMemberRepository.findAllByUserIdWithTeam(userId)
            .map { m -> TeamSummaryResponse.of(m.team, m.role) }

    fun getTeam(teamId: Long): TeamResponse =
        TeamResponse.of(findTeamOrThrow(teamId))

    @Transactional
    fun updateTeam(userId: Long, teamId: Long, request: UpdateTeamRequest): TeamResponse {
        requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = findTeamOrThrow(teamId)
        team.update(request.name, request.description, request.iconUrl)
        return TeamResponse.of(team)
    }

    @Transactional
    fun deleteTeam(userId: Long, teamId: Long) {
        requireRole(teamId, userId, TeamRole.OWNER)
        teamRepository.deleteById(teamId)
    }
}
