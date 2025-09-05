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

    private fun toResponse(member: ProjectMember) =
        ProjectMemberResponse.of(member, userService.getSummaries(listOf(member.userId))[member.userId])
}
