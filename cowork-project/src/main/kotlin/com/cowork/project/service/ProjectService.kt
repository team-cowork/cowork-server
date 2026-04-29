package com.cowork.project.service

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.ProjectStatus
import com.cowork.project.dto.*
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {

    private fun findProjectOrThrow(projectId: Long): Project =
        projectRepository.findById(projectId).orElseThrow {
            ExpectedException("프로젝트를 찾을 수 없습니다. id=$projectId", HttpStatus.NOT_FOUND)
        }

    private fun findMemberOrThrow(memberId: Long): ProjectMember =
        projectMemberRepository.findById(memberId).orElseThrow {
            ExpectedException("프로젝트 멤버를 찾을 수 없습니다. id=$memberId", HttpStatus.NOT_FOUND)
        }

    private fun requireOwner(projectId: Long, userId: Long) {
        if (!projectMemberRepository.existsByProjectIdAndUserIdAndRole(projectId, userId, ProjectMemberRole.OWNER)) {
            throw ExpectedException("해당 작업은 프로젝트 OWNER만 수행할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    private fun requireOwnerOrEditor(projectId: Long, userId: Long) {
        val member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
            ?: throw ExpectedException("프로젝트 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        if (member.role != ProjectMemberRole.OWNER && member.role != ProjectMemberRole.EDITOR) {
            throw ExpectedException("해당 작업은 프로젝트 OWNER 또는 EDITOR만 수행할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
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

    fun getProject(projectId: Long): ProjectDetailResponse {
        val project = findProjectOrThrow(projectId)
        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResponse.of(project, memberCount)
    }

    @Transactional
    fun updateProject(userId: Long, projectId: Long, request: UpdateProjectRequest): ProjectResponse {
        requireOwnerOrEditor(projectId, userId)
        val project = findProjectOrThrow(projectId)

        request.name?.let { project.updateName(it) }
        request.description?.let { project.updateDescription(it) }
        request.status?.let { project.updateStatus(parseStatus(it)) }

        return ProjectResponse.of(project)
    }

    @Transactional
    fun deleteProject(userId: Long, projectId: Long) {
        requireOwner(projectId, userId)
        val project = findProjectOrThrow(projectId)
        projectRepository.delete(project)
    }

    fun getProjectsByTeamId(teamId: Long, pageable: Pageable): Page<ProjectResponse> =
        projectRepository.findByTeamId(teamId, pageable).map { ProjectResponse.of(it) }

    fun getMyProjects(userId: Long, pageable: Pageable): Page<ProjectResponse> =
        projectRepository.findProjectsByMemberUserId(userId, pageable)
            .map { ProjectResponse.of(it) }

    @Transactional
    fun addMember(userId: Long, projectId: Long, request: AddProjectMemberRequest): ProjectMemberResponse {
        requireOwner(projectId, userId)

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

    fun getMembers(projectId: Long): List<ProjectMemberResponse> {
        findProjectOrThrow(projectId)
        return projectMemberRepository.findByProjectId(projectId).map { ProjectMemberResponse.of(it) }
    }

    @Transactional
    fun updateMemberRole(userId: Long, projectId: Long, memberId: Long, request: UpdateProjectMemberRoleRequest): ProjectMemberResponse {
        requireOwner(projectId, userId)
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
        requireOwner(projectId, userId)
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
