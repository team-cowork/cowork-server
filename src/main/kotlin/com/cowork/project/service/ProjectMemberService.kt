package com.cowork.project.service

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.dto.ProjectMemberResponse
import com.cowork.project.event.ProjectMemberAddedEvent
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.team.service.TeamAccessChecker
import com.cowork.user.service.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트 멤버 추가, 역할 변경, 제외를 담당한다.
 *
 * - 팀 멤버만 프로젝트에 추가할 수 있다.
 * - 마지막 소유자는 프로젝트를 떠날 수 없다.
 */
@Service
class ProjectMemberService(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessChecker: ProjectAccessChecker,
    private val teamAccessChecker: TeamAccessChecker,
    private val userService: UserService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun addMember(projectId: Long, actorId: Long, targetUserId: Long, role: ProjectMemberRole): ProjectMemberResponse {
        val project = projectAccessChecker.requireManageable(projectId, actorId)
        if (!teamAccessChecker.isMember(project.teamId, targetUserId)) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "팀 멤버만 프로젝트에 추가할 수 있습니다.")
        }
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, targetUserId)) {
            throw BusinessException(HttpStatus.CONFLICT, "이미 프로젝트에 속한 멤버입니다.")
        }
        val member = projectMemberRepository.save(ProjectMember(projectId = projectId, userId = targetUserId, role = role))
        eventPublisher.publishEvent(ProjectMemberAddedEvent(projectId, project.name, targetUserId, actorId))
        return toResponse(member)
    }

    @Transactional(readOnly = true)
    fun getMembers(projectId: Long, actorId: Long): List<ProjectMemberResponse> {
        projectAccessChecker.requireReadable(projectId, actorId)
        val members = projectMemberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
        val users = userService.getSummaries(members.map { it.userId })
        return members.map { ProjectMemberResponse.of(it, users[it.userId]) }
    }

    @Transactional(readOnly = true)
    fun getMyMembership(projectId: Long, actorId: Long): ProjectMemberResponse {
        projectAccessChecker.requireReadable(projectId, actorId)
        return toResponse(findMember(projectId, actorId))
    }

    @Transactional
    fun changeRole(projectId: Long, actorId: Long, targetUserId: Long, role: ProjectMemberRole): ProjectMemberResponse {
        projectAccessChecker.requireManageable(projectId, actorId)
        val member = findMember(projectId, targetUserId)
        if (member.role == ProjectMemberRole.OWNER && role != ProjectMemberRole.OWNER) {
            ensureAnotherOwnerExists(projectId)
        }
        member.changeRole(role)
        return toResponse(member)
    }

    @Transactional
    fun removeMember(projectId: Long, actorId: Long, targetUserId: Long) {
        if (actorId == targetUserId) {
            projectAccessChecker.requireReadable(projectId, actorId)
        } else {
            projectAccessChecker.requireManageable(projectId, actorId)
        }
        val member = findMember(projectId, targetUserId)
        if (member.role == ProjectMemberRole.OWNER) {
            ensureAnotherOwnerExists(projectId)
        }
        projectMemberRepository.delete(member)
    }

    private fun ensureAnotherOwnerExists(projectId: Long) {
        if (projectMemberRepository.countByProjectIdAndRole(projectId, ProjectMemberRole.OWNER) <= 1) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "프로젝트에는 최소 한 명의 소유자가 있어야 합니다.")
        }
    }

    private fun findMember(projectId: Long, userId: Long): ProjectMember =
        projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "프로젝트 멤버를 찾을 수 없습니다.")

    private fun toResponse(member: ProjectMember) =
        ProjectMemberResponse.of(member, userService.getSummaries(listOf(member.userId))[member.userId])
}
