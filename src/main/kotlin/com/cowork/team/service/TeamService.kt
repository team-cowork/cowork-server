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
import com.cowork.team.repository.TeamRoleAssignmentRepository
import com.cowork.team.repository.TeamRoleRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 팀 생성, 조회, 수정, 삭제와 팀 아이콘을 담당한다.
 *
 * - 팀을 만든 사용자는 소유자가 된다.
 * - 비공개 팀은 멤버만 조회할 수 있다.
 * - 팀을 삭제할 때는 하위 리소스 정리 이벤트를 먼저 발행한 뒤 팀을 지운다.
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleRepository: TeamRoleRepository,
    private val teamRoleAssignmentRepository: TeamRoleAssignmentRepository,
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
    fun getMyTeams(userId: Long): List<TeamResponse> {
        val teams = teamRepository.findAllByMemberUserId(userId)
        if (teams.isEmpty()) return emptyList()
        val roles = teamMemberRepository.findAllByUserId(userId).associate { it.teamId to it.role }
        val counts = teamMemberRepository.countByTeamIds(teams.map { it.id }).associate { it.teamId to it.count }
        return teams.map { team ->
            TeamResponse.of(
                team = team,
                iconUrl = team.iconKey?.let(storageService::createDownloadUrl),
                memberCount = counts[team.id] ?: 0,
                myRole = roles[team.id],
            )
        }
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
        eventPublisher.publishEvent(TeamDeletedEvent(teamId))
        teamRoleAssignmentRepository.deleteAllByTeamId(teamId)
        teamRoleRepository.deleteAllByTeamId(teamId)
        teamMemberRepository.deleteAllByTeamId(teamId)
        teamRepository.delete(team)
        iconKey?.let(storageService::delete)
    }

    fun createIconUploadUrl(userId: Long, request: PresignedUploadRequest): PresignedUploadResponse {
        ImageUploadPolicy.validateSize(request.fileSize)
        val extension = ImageUploadPolicy.extensionOf(request.contentType)
        val objectKey = "teams/icons/$userId/${UUID.randomUUID()}.$extension"
        return PresignedUploadResponse(storageService.createUploadUrl(objectKey, request.contentType), objectKey)
    }

    @Transactional
    fun updateIcon(teamId: Long, userId: Long, objectKey: String): TeamResponse {
        val member = teamAccessChecker.requirePermission(teamId, userId, TeamPermission.MANAGE_TEAM)
        if (!objectKey.startsWith("teams/icons/$userId/")) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "유효하지 않은 이미지 키입니다.")
        }
        val size = storageService.findObjectSize(objectKey)
            ?: throw BusinessException(HttpStatus.CONFLICT, "이미지가 아직 업로드되지 않았습니다.")
        ImageUploadPolicy.validateSize(size)

        val team = findTeam(teamId)
        val previousKey = team.iconKey
        team.changeIcon(objectKey)
        previousKey?.let(storageService::delete)
        return toResponse(team, member.role)
    }

    @Transactional
    fun deleteIcon(teamId: Long, userId: Long) {
        teamAccessChecker.requirePermission(teamId, userId, TeamPermission.MANAGE_TEAM)
        val team = findTeam(teamId)
        val iconKey = team.iconKey
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "팀 아이콘이 없습니다.")
        team.changeIcon(null)
        storageService.delete(iconKey)
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
