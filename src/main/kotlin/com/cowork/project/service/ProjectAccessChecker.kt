package com.cowork.project.service

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class ProjectAccessChecker(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamAccessChecker: TeamAccessChecker,
) {

    fun findProject(projectId: Long): Project =
        projectRepository.findByIdOrNull(projectId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.")

    fun requireReadable(projectId: Long, userId: Long): Project {
        val project = findProject(projectId)
        teamAccessChecker.requireMember(project.teamId, userId)
        return project
    }

    fun requireEditable(projectId: Long, userId: Long): Project {
        val project = findProject(projectId)
        if (!canManage(project, userId) && projectMemberRepository.findByProjectIdAndUserId(projectId, userId)?.role?.canEdit != true) {
            throw BusinessException(HttpStatus.FORBIDDEN, "프로젝트를 수정할 권한이 없습니다.")
        }
        return project
    }

    fun requireManageable(projectId: Long, userId: Long): Project {
        val project = findProject(projectId)
        if (!canManage(project, userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "프로젝트를 관리할 권한이 없습니다.")
        }
        return project
    }

    private fun canManage(project: Project, userId: Long): Boolean {
        val teamMember = teamAccessChecker.requireMember(project.teamId, userId)
        if (teamAccessChecker.hasPermission(teamMember, TeamPermission.MANAGE_PROJECTS)) {
            return true
        }
        return projectMemberRepository.findByProjectIdAndUserId(project.id, userId)?.role == ProjectMemberRole.OWNER
    }
}
