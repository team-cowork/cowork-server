package com.cowork.project.domain.calendar.service

import com.cowork.project.domain.calendar.model.CalendarEditScope
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarCategoryReqDto
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarEventReqDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarCategoryResDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarEventResDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarOccurrenceResDto
import java.time.Instant

interface ManageProjectCalendarEventService {
    fun create(userId: Long, projectId: Long, request: SaveProjectCalendarEventReqDto): ProjectCalendarEventResDto

    /** 바뀐 원본을 돌려준다. "이후 모두"로 나누면 새로 만든 일정을 돌려준다. */
    fun update(
        userId: Long,
        projectId: Long,
        eventId: Long,
        scope: CalendarEditScope,
        occurrenceStartAt: Instant?,
        request: SaveProjectCalendarEventReqDto,
    ): ProjectCalendarEventResDto

    fun delete(userId: Long, projectId: Long, eventId: Long, scope: CalendarEditScope, occurrenceStartAt: Instant?)
}

interface QueryProjectCalendarEventsService {
    fun get(userId: Long, projectId: Long, eventId: Long): ProjectCalendarEventResDto

    fun query(userId: Long, projectId: Long, from: Instant, to: Instant): List<ProjectCalendarOccurrenceResDto>
}

interface ManageProjectCalendarCategoryService {
    fun create(userId: Long, projectId: Long, request: SaveProjectCalendarCategoryReqDto): ProjectCalendarCategoryResDto

    fun update(
        userId: Long,
        projectId: Long,
        categoryId: Long,
        request: SaveProjectCalendarCategoryReqDto,
    ): ProjectCalendarCategoryResDto

    fun delete(userId: Long, projectId: Long, categoryId: Long)
}

interface QueryProjectCalendarCategoriesService {
    fun execute(userId: Long, projectId: Long): List<ProjectCalendarCategoryResDto>
}
