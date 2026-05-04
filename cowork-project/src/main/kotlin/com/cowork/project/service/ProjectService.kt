package com.cowork.project.service

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.ProjectStatus
import com.cowork.project.dto.*
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.repository.TeamMembershipRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

private const val TEAM_ROLE_OWNER = "OWNER"
private const val TEAM_ROLE_ADMIN = "ADMIN"

@Service
@Transactional(readOnly = true)
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
) {

    private fun findProjectOrThrow(projectId: Long): Project =
        projectRepository.findById(projectId).orElseThrow {
            ExpectedException("프로젝트를 찾을 수 없습니다. id=$projectId", HttpStatus.NOT_FOUND)
        }

    private fun findMemberOrThrow(memberId: Long): ProjectMember =
        projectMemberRepository.findById(memberId).orElseThrow {
            ExpectedException("프로젝트 멤버를 찾을 수 없습니다. id=$memberId", HttpStatus.NOT_FOUND)
        }

    private fun teamRoleOf(teamId: Long, userId: Long): String? =
        teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)?.role

    private fun requireTeamMember(teamId: Long, userId: Long) {
        teamRoleOf(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
    }

    private fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean =
        teamRoleOf(teamId, userId) in setOf(TEAM_ROLE_OWNER, TEAM_ROLE_ADMIN)

    private fun requireProjectModifier(project: Project, userId: Long) {
        val role = projectMemberRepository.findByProjectIdAndUserId(project.id, userId)?.role
        if (role == ProjectMemberRole.OWNER || role == ProjectMemberRole.EDITOR) return
        if (isTeamOwnerOrAdmin(project.teamId, userId)) return
        throw ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    private fun requireProjectOwner(project: Project, userId: Long) {
        val role = projectMemberRepository.findByProjectIdAndUserId(project.id, userId)?.role
        if (role == ProjectMemberRole.OWNER) return
        if (isTeamOwnerOrAdmin(project.teamId, userId)) return
        throw ExpectedException("해당 작업은 프로젝트 OWNER만 수행할 수 있습니다.", HttpStatus.FORBIDDEN)
    }

    private fun parseRole(role: String): ProjectMemberRole =
        try {
            ProjectMemberRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 역할입니다: $role", HttpStatus.BAD_REQUEST)
        }

    private fun parseStatus(status: String): ProjectStatus =
        try {
            ProjectStatus.valueOf(status.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 상태입니다: $status", HttpStatus.BAD_REQUEST)
        }

    @Transactional
    fun createProject(userId: Long, request: CreateProjectRequest): ProjectResponse {
        requireTeamMember(request.teamId, userId)

        val project = projectRepository.save(
            Project(
                teamId = request.teamId,
                name = request.name,
                description = request.description,
                createdBy = userId,
            )
        )

        projectMemberRepository.save(
            ProjectMember(
                projectId = project.id,
                userId = userId,
                role = ProjectMemberRole.OWNER,
            )
        )

        return ProjectResponse.of(project)
    }

    fun getProject(userId: Long, projectId: Long): ProjectDetailResponse {
        val project = findProjectOrThrow(projectId)
        requireTeamMember(project.teamId, userId)
        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResponse.of(project, memberCount)
    }

    @Transactional
    fun updateProject(userId: Long, projectId: Long, request: UpdateProjectRequest): ProjectResponse {
        val project = findProjectOrThrow(projectId)
        requireProjectModifier(project, userId)

        request.name?.let { project.updateName(it) }
        request.description?.let { project.updateDescription(it) }
        request.status?.let { project.updateStatus(parseStatus(it)) }

        return ProjectResponse.of(project)
    }

    @Transactional
    fun deleteProject(userId: Long, projectId: Long) {
        val project = findProjectOrThrow(projectId)
        requireProjectOwner(project, userId)
        projectRepository.delete(project)
    }

    fun getProjectsByTeamId(userId: Long, teamId: Long, pageable: Pageable): Page<ProjectResponse> {
        requireTeamMember(teamId, userId)
        return projectRepository.findByTeamId(teamId, pageable).map { ProjectResponse.of(it) }
    }

    fun getMyProjects(userId: Long, pageable: Pageable): Page<ProjectResponse> =
        projectRepository.findProjectsByMemberUserId(userId, pageable)
            .map { ProjectResponse.of(it) }

    @Transactional
    fun addMember(userId: Long, projectId: Long, request: AddProjectMemberRequest): ProjectMemberResponse {
        val project = findProjectOrThrow(projectId)
        requireProjectOwner(project, userId)

        teamRoleOf(project.teamId, request.userId)
            ?: throw ExpectedException("추가 대상이 팀 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)

        val role = parseRole(request.role)
        if (role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER 역할은 멤버 추가로 부여할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val existingMember = projectMemberRepository.findByProjectIdAndUserId(projectId, request.userId)
        if (existingMember != null) {
            throw ExpectedException("이미 프로젝트에 참여 중인 사용자입니다.", HttpStatus.CONFLICT)
        }

        val member = projectMemberRepository.save(
            ProjectMember(
                projectId = projectId,
                userId = request.userId,
                role = role,
            )
        )

        return ProjectMemberResponse.of(member)
    }

    fun getMembers(userId: Long, projectId: Long): List<ProjectMemberResponse> {
        val project = findProjectOrThrow(projectId)
        requireTeamMember(project.teamId, userId)
        return projectMemberRepository.findByProjectId(projectId).map { ProjectMemberResponse.of(it) }
    }

    @Transactional
    fun updateMemberRole(userId: Long, projectId: Long, memberId: Long, request: UpdateProjectMemberRoleRequest): ProjectMemberResponse {
        val project = findProjectOrThrow(projectId)
        requireProjectOwner(project, userId)
        val member = findMemberOrThrow(memberId)

        if (member.projectId != projectId) {
            throw ExpectedException("해당 프로젝트의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        val role = parseRole(request.role)
        if (role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER 역할은 역할 변경으로 부여할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        if (member.role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER의 역할은 변경할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        member.updateRole(role)

        return ProjectMemberResponse.of(member)
    }

    @Transactional
    fun removeMember(userId: Long, projectId: Long, memberId: Long) {
        val project = findProjectOrThrow(projectId)
        requireProjectOwner(project, userId)
        val member = findMemberOrThrow(memberId)

        if (member.projectId != projectId) {
            throw ExpectedException("해당 프로젝트의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (member.role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER는 제거할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        projectMemberRepository.delete(member)
    }
}
