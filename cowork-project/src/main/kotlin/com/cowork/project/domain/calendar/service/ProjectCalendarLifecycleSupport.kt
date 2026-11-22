package com.cowork.project.domain.calendar.service

import com.cowork.project.domain.calendar.event.ProjectCalendarEventPublisher
import com.cowork.project.domain.calendar.event.ProjectReadableUserStatePublisher
import com.cowork.project.domain.calendar.repository.ProjectCalendarCategoryRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.project.entity.Project
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 프로젝트를 지울 때 일정은 삭제 이력으로 남겨 DELETE를 발행하고, 카테고리는 지운다. */
@Component
class ProjectCalendarLifecycleSupport(
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
    private val projectCalendarEventPublisher: ProjectCalendarEventPublisher,
    private val projectReadableUserStatePublisher: ProjectReadableUserStatePublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteByProjects(projects: Collection<Project>, occurredAt: Instant) {
        if (projects.isEmpty()) return
        projectCalendarEventRepository.findActiveByProjectIdsForUpdate(projects.map { it.id }).forEach {
            it.deleted = true
            projectCalendarEventPublisher.publishChanged(it, null, occurredAt)
        }
        projectCalendarCategoryRepository.deleteAllByProjectIds(projects.map { it.id })
        projects.forEach { projectReadableUserStatePublisher.publishDeleted(it.id, it.teamId, occurredAt) }
    }
}
