package com.cowork.project.service

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.dto.CreateProjectRequest
import com.cowork.project.dto.ProjectResponse
import com.cowork.project.dto.UpdateProjectRequest
import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로젝트 생성, 조회, 수정, 삭제와 순서 변경을 담당한다.
 *
 * - 프로젝트를 만든 사용자는 프로젝트 소유자가 된다.
 * - 순서를 바꿀 때는 팀의 모든 프로젝트를 중복 없이 받아야 한다.
 */
@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessChecker: ProjectAccessChecker,
    private val teamAccessChecker: TeamAccessChecker,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun create(userId: Long, request: CreateProjectRequest): ProjectResponse {
        teamAccessChecker.requireMember(request.teamId, userId)
        val project = projectRepository.save(
            Project(
                teamId = request.teamId,
                name = request.name,
                description = request.description,
                githubRepoUrl = request.githubRepoUrl?.ifBlank { null },
                position = projectRepository.findMaxPosition(request.teamId) + 1,
                createdBy = userId,
            ),
        )
        projectMemberRepository.save(ProjectMember(projectId = project.id, userId = userId, role = ProjectMemberRole.OWNER))
        return ProjectResponse.from(project)
    }

    @Transactional(readOnly = true)
    fun getProject(projectId: Long, userId: Long): ProjectResponse =
        ProjectResponse.from(projectAccessChecker.requireReadable(projectId, userId))

    @Transactional(readOnly = true)
    fun getTeamProjects(teamId: Long, userId: Long): List<ProjectResponse> {
        teamAccessChecker.requireMember(teamId, userId)
        return projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId).map(ProjectResponse::from)
    }

    @Transactional(readOnly = true)
    fun getMyProjects(userId: Long): List<ProjectResponse> =
        projectRepository.findAllByMemberUserId(userId).map(ProjectResponse::from)

    @Transactional
    fun update(projectId: Long, userId: Long, request: UpdateProjectRequest): ProjectResponse {
        val project = projectAccessChecker.requireEditable(projectId, userId)
        project.update(request.name, request.description, request.githubRepoUrl, request.status)
        return ProjectResponse.from(project)
    }

    @Transactional
    fun delete(projectId: Long, userId: Long) {
        val project = projectAccessChecker.requireManageable(projectId, userId)
        eventPublisher.publishEvent(ProjectDeletedEvent(project.id, project.teamId))
        projectMemberRepository.deleteAllByProjectId(project.id)
        projectRepository.delete(project)
    }

    @Transactional
    fun reorder(teamId: Long, userId: Long, projectIds: List<Long>): List<ProjectResponse> {
        teamAccessChecker.requirePermission(teamId, userId, TeamPermission.MANAGE_PROJECTS)
        val projects = projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
        if (projectIds.size != projects.size || projectIds.toSet() != projects.map { it.id }.toSet()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "팀의 모든 프로젝트를 중복 없이 전달해야 합니다.")
        }
        val projectById = projects.associateBy { it.id }
        projectIds.forEachIndexed { index, id -> projectById.getValue(id).moveTo(index) }
        return projectIds.map { ProjectResponse.from(projectById.getValue(it)) }
    }
}
