package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.UpdateProjectService
import com.cowork.project.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateProjectServiceImpl(
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
) : UpdateProjectService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, request: UpdateProjectReqDto): ProjectResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        request.name?.let { project.updateName(it) }
        request.description?.let { project.updateDescription(it) }
        request.status?.let { project.updateStatus(it) }

        afterCommit { projectEventPublisher.publishUpdated(project) }

        return ProjectResDto.of(project)
    }
}
