package com.cowork.project.domain.calendar.service.impl

import com.cowork.project.domain.calendar.entity.ProjectCalendarCategory
import com.cowork.project.domain.calendar.entity.ProjectCalendarEvent
import com.cowork.project.domain.calendar.event.ProjectCalendarEventPublisher
import com.cowork.project.domain.calendar.model.CalendarAction
import com.cowork.project.domain.calendar.model.CalendarEditScope
import com.cowork.project.domain.calendar.model.CalendarOccurrenceException
import com.cowork.project.domain.calendar.model.CalendarRecurrence
import com.cowork.project.domain.calendar.model.CalendarRecurrenceExpander
import com.cowork.project.domain.calendar.model.CalendarReminderPolicy
import com.cowork.project.domain.calendar.model.CalendarSeriesSplitter
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarEventReqDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarEventResDto
import com.cowork.project.domain.calendar.repository.ProjectCalendarCategoryRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.calendar.service.ManageProjectCalendarEventService
import com.cowork.project.domain.calendar.service.ProjectCalendarAccessGuard
import com.cowork.project.domain.project.entity.Project
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class ManageProjectCalendarEventServiceImpl(
    private val projectCalendarAccessGuard: ProjectCalendarAccessGuard,
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
    private val projectCalendarEventPublisher: ProjectCalendarEventPublisher,
) : ManageProjectCalendarEventService {

    @Transactional
    override fun create(
        userId: Long,
        projectId: Long,
        request: SaveProjectCalendarEventReqDto,
    ): ProjectCalendarEventResDto {
        val project = projectCalendarAccessGuard.requireCreator(projectId, userId)
        val input = parse(project, request, existingAttendees = emptySet())
        return save(project, userId, input)
    }

    @Transactional
    override fun update(
        userId: Long,
        projectId: Long,
        eventId: Long,
        scope: CalendarEditScope,
        occurrenceStartAt: Instant?,
        request: SaveProjectCalendarEventReqDto,
    ): ProjectCalendarEventResDto {
        val project = projectCalendarAccessGuard.requireReader(projectId, userId)
        val event = findEvent(eventId, projectId)
        projectCalendarAccessGuard.requireOwnerOrPermission(project, event.createdBy, userId, CalendarAction.UPDATE)
        val input = parse(project, request, existingAttendees = event.attendeeUserIds)
        when (effectiveScope(event, scope)) {
            CalendarEditScope.ALL -> applyAll(event, input)

            CalendarEditScope.THIS -> {
                val originalStartAt = requireOccurrence(event, occurrenceStartAt)
                event.putException(
                    CalendarOccurrenceException(
                        originalStartAt,
                        title = input.title,
                        description = input.description,
                        startAt = input.startAt,
                        endAt = input.endAt,
                    ),
                )
                event.refreshRange()
            }

            CalendarEditScope.FOLLOWING -> {
                val originalStartAt = requireOccurrence(event, occurrenceStartAt)
                val originalRecurrence = event.recurrence
                val originalTimeZone = event.timeZone
                val split = CalendarSeriesSplitter.split(event.series(), originalStartAt)
                if (split == null) {
                    applyAll(event, input)
                } else {
                    event.applyRecurrence(split.head)
                    event.replaceExceptions(split.headExceptions)
                    event.refreshRange()
                    publish(event)
                    // 규칙을 바꾸지 않았다면 남은 발생 수를 이어받아 전체 발생 수가 그대로 남게 한다.
                    val tailRecurrence = if (input.recurrence == originalRecurrence) split.tail else input.recurrence
                    // 발생분 위치가 그대로면 예외가 가리키던 발생분도 새 시리즈에 그대로 있으므로 예외를 넘긴다.
                    val occurrencesKept = input.startAt == originalStartAt &&
                        input.timeZone == originalTimeZone &&
                        input.recurrence == originalRecurrence
                    val tailExceptions = if (occurrencesKept) split.tailExceptions else emptyList()
                    return save(project, event.createdBy, input.copy(recurrence = tailRecurrence), tailExceptions)
                }
            }
        }
        return publish(event)
    }

    @Transactional
    override fun delete(
        userId: Long,
        projectId: Long,
        eventId: Long,
        scope: CalendarEditScope,
        occurrenceStartAt: Instant?,
    ) {
        val project = projectCalendarAccessGuard.requireReader(projectId, userId)
        val event = findEvent(eventId, projectId)
        projectCalendarAccessGuard.requireOwnerOrPermission(project, event.createdBy, userId, CalendarAction.DELETE)
        when (effectiveScope(event, scope)) {
            CalendarEditScope.ALL -> event.deleted = true

            CalendarEditScope.THIS -> {
                event.putException(CalendarOccurrenceException(requireOccurrence(event, occurrenceStartAt), true))
                event.refreshRange()
            }

            CalendarEditScope.FOLLOWING -> {
                val split = CalendarSeriesSplitter.split(event.series(), requireOccurrence(event, occurrenceStartAt))
                if (split == null) {
                    event.deleted = true
                } else {
                    event.applyRecurrence(split.head)
                    event.replaceExceptions(split.headExceptions)
                    event.refreshRange()
                }
            }
        }
        publish(event)
    }

    private fun save(
        project: Project,
        createdBy: Long,
        input: EventInput,
        exceptions: List<CalendarOccurrenceException> = emptyList(),
    ): ProjectCalendarEventResDto {
        val event = ProjectCalendarEvent(
            projectId = project.id,
            teamId = project.teamId,
            createdBy = createdBy,
            title = input.title,
            description = input.description,
            startAt = input.startAt,
            endAt = input.endAt,
            allDay = input.allDay,
            timeZone = input.timeZone,
            categoryId = input.categoryId,
            rangeStartAt = input.startAt,
        )
        applyAll(event, input)
        if (exceptions.isNotEmpty()) {
            event.replaceExceptions(exceptions)
            event.refreshRange()
        }
        return publish(projectCalendarEventRepository.save(event))
    }

    private fun applyAll(event: ProjectCalendarEvent, input: EventInput) {
        // 원래 시작 시각이 바뀌면 예외가 가리키던 발생분이 사라지므로 예외를 지운다.
        val occurrencesMoved = event.startAt != input.startAt ||
            event.timeZone != input.timeZone ||
            event.recurrence != input.recurrence
        event.title = input.title
        event.description = input.description
        event.startAt = input.startAt
        event.endAt = input.endAt
        event.allDay = input.allDay
        event.timeZone = input.timeZone
        event.categoryId = input.categoryId
        event.applyRecurrence(input.recurrence)
        event.applyReminders(input.reminders)
        event.attendeeUserIds.retainAll(input.attendees)
        event.attendeeUserIds.addAll(input.attendees)
        if (occurrencesMoved) event.replaceExceptions(emptyList())
        event.refreshRange()
    }

    private fun publish(event: ProjectCalendarEvent): ProjectCalendarEventResDto {
        val category = event.categoryId?.let { projectCalendarCategoryRepository.findById(it).orElse(null) }
        projectCalendarEventPublisher.publishChanged(event, category)
        return ProjectCalendarEventResDto.of(event, category)
    }

    private fun findEvent(eventId: Long, projectId: Long): ProjectCalendarEvent =
        projectCalendarEventRepository.findActiveForUpdate(eventId, projectId)
            ?: throw ExpectedException("일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

    private fun effectiveScope(event: ProjectCalendarEvent, scope: CalendarEditScope): CalendarEditScope =
        if (event.recurrenceFrequency == null) CalendarEditScope.ALL else scope

    private fun requireOccurrence(event: ProjectCalendarEvent, occurrenceStartAt: Instant?): Instant {
        val originalStartAt = occurrenceStartAt?.truncatedTo(ChronoUnit.MICROS)
        if (originalStartAt == null || !CalendarRecurrenceExpander.isOccurrence(event.series(), originalStartAt)) {
            throw ExpectedException("반복 일정의 발생분 시작 시각이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        }
        return originalStartAt
    }

    private data class EventInput(
        val title: String,
        val description: String?,
        val startAt: Instant,
        val endAt: Instant,
        val allDay: Boolean,
        val timeZone: String,
        val categoryId: Long?,
        val attendees: Set<Long>,
        val reminders: List<Int>,
        val recurrence: CalendarRecurrence?,
    )

    private fun parse(
        project: Project,
        request: SaveProjectCalendarEventReqDto,
        existingAttendees: Set<Long>,
    ): EventInput {
        // DB는 마이크로초까지 저장하므로 응답과 발생분 시작 시각이 저장값과 같도록 미리 자른다.
        val startAt = requireNotNull(request.startAt).truncatedTo(ChronoUnit.MICROS)
        val endAt = requireNotNull(request.endAt).truncatedTo(ChronoUnit.MICROS)
        if (endAt.isBefore(startAt)) {
            throw ExpectedException("종료 시각은 시작 시각보다 앞설 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        // `+09:00` 같은 오프셋은 일광 절약 시간을 따르지 못하므로 IANA 지역 ID만 받는다.
        val timeZone = request.timeZone.takeIf { it in ZoneId.getAvailableZoneIds() }
            ?: throw ExpectedException("시간대는 IANA 시간대여야 합니다.", HttpStatus.BAD_REQUEST)
        val reminders = CalendarReminderPolicy.normalize(request.reminderMinutes)
            ?: throw ExpectedException("리마인더는 0, 10, 30, 60, 1440분 전 중 최대 2개만 고를 수 있습니다.", HttpStatus.BAD_REQUEST)
        val recurrence = request.recurrence?.let {
            try {
                CalendarRecurrence(
                    requireNotNull(it.frequency),
                    it.interval,
                    it.byDay,
                    it.until?.truncatedTo(ChronoUnit.MICROS),
                    it.count,
                )
            } catch (_: IllegalArgumentException) {
                throw ExpectedException("반복 규칙이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
            }
        }
        if (recurrence?.until?.isBefore(startAt) == true) {
            throw ExpectedException("반복 종료 시각은 시작 시각보다 앞설 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val category: ProjectCalendarCategory? = request.categoryId?.let {
            projectCalendarCategoryRepository.findByIdAndProjectId(it, project.id)
                ?: throw ExpectedException("일정 카테고리를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val attendees = request.attendeeUserIds.toSortedSet()
        // 이미 참석자인 사용자는 읽기 권한을 잃어도 그대로 둘 수 있다. 리마인더는 받는 시점의 읽기 권한으로 거른다.
        projectCalendarAccessGuard.requireAttendees(project, attendees - existingAttendees)
        return EventInput(
            title = request.title.trim(),
            description = request.description?.trim()?.ifEmpty { null },
            startAt = startAt,
            endAt = endAt,
            allDay = request.allDay,
            timeZone = timeZone,
            categoryId = category?.id,
            attendees = attendees,
            reminders = reminders,
            recurrence = recurrence,
        )
    }
}
