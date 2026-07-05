package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.UpdateProjectService
import com.cowork.project.domain.project.service.support.ProjectEnumParser
import com.cowork.project.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateProjectServiceImpl(
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectEnumParser: ProjectEnumParser,
) : UpdateProjectService {

    override fun updateProject(userId: Long, projectId: Long, request: UpdateProjectReqDto): ProjectResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        request.name?.let { project.updateName(it) }
        request.description?.let { project.updateDescription(it) }
        request.status?.let { project.updateStatus(projectEnumParser.parseStatus(it)) }

        afterCommit { projectEventPublisher.publishUpdated(project) }

        return ProjectResDto.of(project)
    }
}
