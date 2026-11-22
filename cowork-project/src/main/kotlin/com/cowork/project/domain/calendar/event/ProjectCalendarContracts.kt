package com.cowork.project.domain.calendar.event

import java.time.Instant

/** 프로젝트 일정 원본과 예외 상태. 키는 일정 ID다. 반복은 소비자가 펼친다. */
const val PROJECT_CALENDAR_EVENT_STATE_TOPIC = "project.calendar-event.state.v1"

/** 프로젝트별 읽기 가능 사용자 전체 목록. 키는 프로젝트 ID다. */
const val PROJECT_READABLE_USER_STATE_TOPIC = "project.readable-user.state.v1"

enum class CalendarStateEventType { UPSERT, DELETE }

/**
 * [occurredAt]은 일정마다 단조 증가하는 원본 변경 버전이다. 같은 버전은 같은 내용이므로 소비자는 무시해도 된다.
 * 삭제도 [CalendarStateEventType.DELETE] 레코드로 보존하며 Kafka null tombstone을 쓰지 않는다.
 */
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
    val exceptions: List<OccurrenceException>,
    val category: Category?,
    val attendeeUserIds: List<Long>,
    val reminderMinutes: List<Int>,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
) {
    data class Recurrence(
        val frequency: String,
        val interval: Int,
        val byDay: List<String>,
        val until: Instant?,
        val count: Int?,
    )

    data class OccurrenceException(
        val originalStartAt: Instant,
        val cancelled: Boolean,
        val title: String?,
        val description: String?,
        val startAt: Instant?,
        val endAt: Instant?,
    )

    data class Category(val id: Long, val name: String, val colorHex: String)
}

/** 읽기 가능 판정은 `ProjectAccessGuard`와 같다. 프로젝트가 삭제되면 빈 목록의 DELETE 레코드를 보낸다. */
data class ProjectReadableUserStatePayload(
    val eventType: CalendarStateEventType,
    val projectId: Long,
    val teamId: Long,
    val userIds: List<Long>,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
)
