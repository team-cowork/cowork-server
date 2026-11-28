package com.cowork.team.domain.calendar.projection

import com.cowork.team.domain.calendar.model.CalendarOccurrenceException
import com.cowork.team.domain.calendar.model.CalendarRecurrence
import com.cowork.team.domain.calendar.model.CalendarSeries
import com.cowork.team.domain.calendar.model.RecurrenceFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

enum class CalendarStateEventType { UPSERT, DELETE }

/** cowork-project `ProjectCalendarEventStatePayload`와 같은 계약이다. [occurredAt]은 일정마다 단조 증가하는 버전이다. */
data class ProjectCalendarEventStatePayload(
    val eventType: CalendarStateEventType,
    val eventId: Long,
    val projectId: Long,
    val teamId: Long,
    val createdBy: Long,
    val title: String,
    val description: String?,
    val startAt: Instant,
    val endAt: Instant,
    val allDay: Boolean,
    val timeZone: String,
    val recurrence: Recurrence?,
    val exceptions: List<OccurrenceException> = emptyList(),
    val category: Category?,
    val attendeeUserIds: List<Long> = emptyList(),
    val reminderMinutes: List<Int> = emptyList(),
    val occurredAt: Instant,
    val snapshot: Boolean = false,
) {
    data class Recurrence(
        val frequency: String,
        val interval: Int,
        val byDay: List<String> = emptyList(),
        val until: Instant?,
        val count: Int?,
    ) {
        /** 규칙이 올바르지 않으면 [IllegalArgumentException]을 던진다. */
        fun toModel() = CalendarRecurrence(
            RecurrenceFrequency.valueOf(frequency),
            interval,
            byDay.map(DayOfWeek::valueOf).toSet(),
            until,
            count,
        )
    }

    /** [description]이 `null`이면 원본 설명을 따르고, 빈 문자열이면 그 발생분의 설명을 비운다. */
    data class OccurrenceException(
        val originalStartAt: Instant,
        val cancelled: Boolean,
        val title: String?,
        val description: String?,
        val startAt: Instant?,
        val endAt: Instant?,
    ) {
        fun toModel() = CalendarOccurrenceException(originalStartAt, cancelled, title, description, startAt, endAt)
    }

    data class Category(val id: Long, val name: String, val colorHex: String)

    /** 펼칠 원본을 만든다. 시간대나 반복 규칙이 올바르지 않으면 예외를 던진다. */
    fun series() = CalendarSeries(
        startAt,
        endAt,
        ZoneId.of(timeZone),
        recurrence?.toModel(),
        exceptions.map { it.toModel() },
    )
}

/** cowork-project `ProjectReadableUserStatePayload`와 같은 계약이다. 프로젝트가 삭제되면 빈 목록의 DELETE가 온다. */
data class ProjectReadableUserStatePayload(
    val eventType: CalendarStateEventType,
    val projectId: Long,
    val teamId: Long,
    val userIds: List<Long> = emptyList(),
    val occurredAt: Instant,
    val snapshot: Boolean = false,
)
