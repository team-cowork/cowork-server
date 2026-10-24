package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateProjectServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
) : CreateProjectService {

    @Transactional
    override fun execute(userId: Long, request: CreateProjectReqDto): ProjectResDto {
        val initialReadAuthorization = projectAccessGuard.requireProjectCreation(request.teamId, userId, request.isPrivate)

        val project = projectRepository.save(
            Project(
                teamId = request.teamId,
                name = request.name,
                description = request.description,
                position = projectRepository.findMaxPositionByTeamId(request.teamId) + 1,
                createdBy = userId,
                isPrivate = request.isPrivate,
                initialReadMembershipVersion = initialReadAuthorization?.membershipVersion,
                initialReadAuthorizationVersion = initialReadAuthorization?.authorizationVersion,
            ),
        )

        projectEventPublisher.publishCreated(project)

        return ProjectResDto.of(project)
    }
}
