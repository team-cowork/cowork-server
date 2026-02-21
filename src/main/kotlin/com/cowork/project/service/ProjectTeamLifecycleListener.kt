package com.cowork.project.service

import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.team.event.TeamDeletedEvent
import com.cowork.team.event.TeamMemberRemovedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ProjectTeamLifecycleListener(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @EventListener
    fun onTeamDeleted(event: TeamDeletedEvent) {
        val projects = projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(event.teamId)
        if (projects.isEmpty()) return
        projects.forEach { eventPublisher.publishEvent(ProjectDeletedEvent(it.id, it.teamId)) }
        projectMemberRepository.deleteAllByProjectIdIn(projects.map { it.id })
        projectRepository.deleteAll(projects)
    }

    @EventListener
    fun onTeamMemberRemoved(event: TeamMemberRemovedEvent) {
        val projectIds = projectRepository.findIdsByTeamId(event.teamId)
        if (projectIds.isEmpty()) return
        projectMemberRepository.deleteAllByUserIdAndProjectIdIn(event.userId, projectIds)
    }
}
