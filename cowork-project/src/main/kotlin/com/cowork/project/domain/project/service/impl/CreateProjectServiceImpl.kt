package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateProjectServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
) : CreateProjectService {

    override fun createProject(userId: Long, request: CreateProjectReqDto): ProjectResDto {
        projectAccessGuard.requireTeamMember(request.teamId, userId)

        val project = projectRepository.save(
            Project(
                teamId = request.teamId,
                name = request.name,
                description = request.description,
                position = projectRepository.findMaxPositionByTeamId(request.teamId) + 1,
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

        afterCommit { projectEventPublisher.publishCreated(project) }

        return ProjectResDto.of(project)
    }
}
