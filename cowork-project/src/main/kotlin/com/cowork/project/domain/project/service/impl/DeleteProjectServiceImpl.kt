package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.DeleteProjectService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteProjectServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
) : DeleteProjectService {

    override fun execute(userId: Long, projectId: Long) {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)
        projectRepository.delete(project)
        afterCommit { projectEventPublisher.publishDeleted(project) }
    }
}
