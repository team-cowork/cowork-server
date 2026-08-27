package com.cowork.project.domain.project.service.impl

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
) : DeleteProjectService {

    @Transactional
    override fun execute(userId: Long, projectId: Long) {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)
        val memberUserIds = projectMemberRepository.findByProjectId(projectId).map { it.userId }
        projectRepository.delete(project)
        val occurredAt = Instant.now()
        memberUserIds.forEach { memberUserId ->
            projectMemberEventPublisher.publishRemoved(projectId, memberUserId, occurredAt)
        }
        projectEventPublisher.publishDeleted(project, occurredAt)
    }
}
