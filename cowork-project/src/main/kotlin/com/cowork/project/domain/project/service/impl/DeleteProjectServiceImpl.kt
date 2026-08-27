package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.service.ProjectGithubRepoDeletionSupport
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.DeleteProjectService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeleteProjectServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
    private val projectAccessGuard: ProjectAccessGuard,
    private val repoDeletionSupport: ProjectGithubRepoDeletionSupport,
) : DeleteProjectService {

    @Transactional
    override fun execute(userId: Long, projectId: Long) {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)
        val members = projectMemberRepository.findAllByProjectIdForUpdate(projectId)
        val occurredAt = Instant.now()
        repoDeletionSupport.deleteByProjectIds(listOf(projectId), occurredAt)
        members.forEach { projectMemberEventPublisher.publishRemoved(it, occurredAt) }
        projectEventPublisher.publishDeleted(project, occurredAt)
        projectRepository.delete(project)
    }
}
