package com.cowork.team.domain.calendar.projection

import com.cowork.team.domain.calendar.model.CalendarCategory
import com.cowork.team.domain.calendar.model.CalendarEntry
import com.cowork.team.domain.calendar.model.CalendarRecurrenceExpander
import com.cowork.team.domain.calendar.model.CalendarSeries
import com.cowork.team.domain.calendar.model.CalendarSource
import com.cowork.team.global.toProjectionPrecision
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.ZoneId

/** 같은 버전은 같은 내용이므로 더 새 버전만 반영한다. snapshot이 같은 버전을 다시 보내도 바뀌는 것이 없다. */
private fun isNewerVersion(existing: Instant?, incoming: Instant): Boolean =
    existing == null || incoming.toProjectionPrecision().isAfter(existing.toProjectionPrecision())

/** 프로젝트 일정과 읽기 가능 사용자 projection을 반영하고 팀 캘린더 원본으로 바꾼다. 트랜잭션은 호출자가 연다. */
@Component
class ProjectCalendarProjectionStore(
    private val projectCalendarEventProjectionRepository: ProjectCalendarEventProjectionRepository,
    private val projectReaderStateProjectionRepository: ProjectReaderStateProjectionRepository,
    private val objectMapper: ObjectMapper,
) {
    /** [series]는 소비자가 계약을 검사하며 [payload]로 만든 원본이다. */
    fun applyEvent(payload: ProjectCalendarEventStatePayload, series: CalendarSeries) {
        val existing = projectCalendarEventProjectionRepository.findById(payload.eventId).orElse(null)
        if (!isNewerVersion(existing?.sourceOccurredAt, payload.occurredAt)) return
        val projection = existing ?: ProjectCalendarEventProjection(
            eventId = payload.eventId,
            projectId = payload.projectId,
            teamId = payload.teamId,
            sourceOccurredAt = payload.occurredAt,
        )
        val (rangeStart, rangeEnd) = CalendarRecurrenceExpander.bounds(series)
        projection.apply {
            projectId = payload.projectId
            teamId = payload.teamId
            createdBy = payload.createdBy
            title = payload.title
            description = payload.description
            startAt = payload.startAt
            endAt = payload.endAt
            allDay = payload.allDay
            timeZone = payload.timeZone
            recurrenceJson = payload.recurrence?.let(objectMapper::writeValueAsString)
            exceptionsJson = objectMapper.writeValueAsString(payload.exceptions)
            categoryId = payload.category?.id
            categoryName = payload.category?.name
            categoryColorHex = payload.category?.colorHex
            attendeeUserIds = payload.attendeeUserIds.distinct().sorted().joinToString(",")
            reminderMinutes = payload.reminderMinutes.joinToString(",")
            rangeStartAt = rangeStart
            rangeEndAt = rangeEnd
            deleted = payload.eventType == CalendarStateEventType.DELETE
            sourceOccurredAt = payload.occurredAt.toProjectionPrecision()
        }
        projectCalendarEventProjectionRepository.save(projection)
    }

    fun applyReaders(payload: ProjectReadableUserStatePayload) {
        val existing = projectReaderStateProjectionRepository.findById(payload.projectId).orElse(null)
        if (!isNewerVersion(existing?.sourceOccurredAt, payload.occurredAt)) return
        val deleted = payload.eventType == CalendarStateEventType.DELETE
        val userIds = if (deleted) emptySet() else payload.userIds.toSet()
        val state = existing ?: ProjectReaderStateProjection(
            projectId = payload.projectId,
            teamId = payload.teamId,
            sourceOccurredAt = payload.occurredAt,
        )
        state.teamId = payload.teamId
        state.deleted = deleted
        state.sourceOccurredAt = payload.occurredAt.toProjectionPrecision()
        state.userIds.retainAll(userIds)
        state.userIds.addAll(userIds)
        projectReaderStateProjectionRepository.save(state)
    }

    fun readableProjectIds(teamId: Long, userId: Long): Set<Long> =
        projectReaderStateProjectionRepository.findReadableProjectIds(teamId, userId).toSet()

    fun findEntries(teamId: Long, projectIds: Collection<Long>, from: Instant, to: Instant): List<CalendarEntry> {
        if (projectIds.isEmpty()) return emptyList()
        return projectCalendarEventProjectionRepository.findActiveInRange(teamId, projectIds, from, to).map(::toEntry)
    }

    private fun toEntry(projection: ProjectCalendarEventProjection): CalendarEntry {
        val recurrence = projection.recurrenceJson
            ?.let { objectMapper.readValue<ProjectCalendarEventStatePayload.Recurrence>(it) }
        val exceptions = objectMapper
            .readValue<List<ProjectCalendarEventStatePayload.OccurrenceException>>(projection.exceptionsJson)
        val categoryId = projection.categoryId
        return CalendarEntry(
            source = CalendarSource.PROJECT,
            eventId = projection.eventId,
            projectId = projection.projectId,
            createdBy = projection.createdBy,
            title = projection.title,
            description = projection.description,
            allDay = projection.allDay,
            timeZone = projection.timeZone,
            series = CalendarSeries(
                projection.startAt,
                projection.endAt,
                ZoneId.of(projection.timeZone),
                recurrence?.toModel(),
                exceptions.map { it.toModel() },
            ),
            category = categoryId?.let {
                CalendarCategory(it, projection.categoryName.orEmpty(), projection.categoryColorHex.orEmpty())
            },
            attendeeUserIds = projection.attendeeUserIds.split(',').filter { it.isNotBlank() }.map(String::toLong),
            reminderMinutes = projection.reminderMinutes.split(',').filter { it.isNotBlank() }.map(String::toInt),
        )
    }
}
