package com.cowork.project.domain.calendar.service.impl

import com.cowork.project.domain.calendar.model.CalendarRangePolicy
import com.cowork.project.domain.calendar.model.CalendarRecurrenceExpander
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarEventResDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarOccurrenceResDto
import com.cowork.project.domain.calendar.repository.ProjectCalendarCategoryRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.calendar.service.ProjectCalendarAccessGuard
import com.cowork.project.domain.calendar.service.QueryProjectCalendarEventsService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

@Service
class QueryProjectCalendarEventsServiceImpl(
    private val projectCalendarAccessGuard: ProjectCalendarAccessGuard,
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
) : QueryProjectCalendarEventsService {

    @Transactional(readOnly = true)
    override fun get(userId: Long, projectId: Long, eventId: Long): ProjectCalendarEventResDto {
        projectCalendarAccessGuard.requireReader(projectId, userId)
        val event = projectCalendarEventRepository.findById(eventId).orElse(null)
            ?.takeIf { it.projectId == projectId && !it.deleted }
            ?: throw ExpectedException("일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val category = event.categoryId?.let { projectCalendarCategoryRepository.findById(it).orElse(null) }
        return ProjectCalendarEventResDto.of(event, category)
    }

    @Transactional(readOnly = true)
    override fun query(
        userId: Long,
        projectId: Long,
        from: Instant,
        to: Instant,
    ): List<ProjectCalendarOccurrenceResDto> {
        if (!CalendarRangePolicy.isValid(from, to)) {
            throw ExpectedException("조회 기간은 시작이 끝보다 앞서고 최대 93일이어야 합니다.", HttpStatus.BAD_REQUEST)
        }
        projectCalendarAccessGuard.requireReader(projectId, userId)
        val events = projectCalendarEventRepository.findActiveInRange(projectId, from, to)
        val categories = projectCalendarCategoryRepository.findAllByProjectIdOrderByIdAsc(projectId).associateBy {
            it.id
        }
        return events.flatMap { event ->
            CalendarRecurrenceExpander.expand(event.series(), from, to).map {
                ProjectCalendarOccurrenceResDto.of(event, it, categories[event.categoryId])
            }
        }.sortedWith(compareBy({ it.startAt }, { it.eventId }, { it.originalStartAt }))
    }
}
