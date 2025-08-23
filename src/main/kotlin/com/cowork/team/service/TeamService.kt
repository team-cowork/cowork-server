package com.cowork.team.service

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.ImageUploadPolicy
import com.cowork.global.storage.PresignedUploadRequest
import com.cowork.global.storage.PresignedUploadResponse
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
import java.util.UUID

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

    @Transactional(readOnly = true)
    fun getMyTeams(userId: Long): List<TeamResponse> =
        teamRepository.findAllByMemberUserId(userId).map { team ->
            toResponse(team, teamMemberRepository.findByTeamIdAndUserId(team.id, userId)?.role)
        }

    @Transactional(readOnly = true)
    fun getTeam(teamId: Long, userId: Long): TeamResponse {
        val team = findTeam(teamId)
        val member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
        if (member == null && team.visibility == TeamVisibility.PRIVATE) {
            throw BusinessException(HttpStatus.FORBIDDEN, "비공개 팀입니다.")
        }
        return toResponse(team, member?.role)
    }

    @Transactional
    fun update(teamId: Long, userId: Long, request: UpdateTeamRequest): TeamResponse {
        val member = teamAccessChecker.requirePermission(teamId, userId, TeamPermission.MANAGE_TEAM)
        val team = findTeam(teamId)
        team.update(request.name, request.description, request.visibility)
        return toResponse(team, member.role)
    }

    @Transactional
    fun delete(teamId: Long, userId: Long) {
        teamAccessChecker.requireOwner(teamId, userId)
        val team = findTeam(teamId)
        val iconKey = team.iconKey
        teamMemberRepository.deleteAllByTeamId(teamId)
        teamRepository.delete(team)
        iconKey?.let(storageService::delete)
        eventPublisher.publishEvent(TeamDeletedEvent(teamId))
    }

    fun createIconUploadUrl(userId: Long, request: PresignedUploadRequest): PresignedUploadResponse {
        ImageUploadPolicy.validateSize(request.fileSize)
        val extension = ImageUploadPolicy.extensionOf(request.contentType)
        val objectKey = "teams/icons/$userId/${UUID.randomUUID()}.$extension"
        return PresignedUploadResponse(storageService.createUploadUrl(objectKey, request.contentType), objectKey)
    }
    private fun findTeam(teamId: Long): Team =
        teamRepository.findByIdOrNull(teamId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다.")

    private fun toResponse(team: Team, myRole: TeamMemberRole?) = TeamResponse.of(
        team = team,
        iconUrl = team.iconKey?.let(storageService::createDownloadUrl),
        memberCount = teamMemberRepository.countByTeamId(team.id),
        myRole = myRole,
    )
}
