package com.cowork.team.domain.calendar.model

import java.time.Instant

enum class CalendarSource { TEAM, PROJECT }

data class CalendarCategory(val id: Long, val name: String, val colorHex: String)

/** 팀 캘린더에 합쳐 보일 일정 원본. 팀 일정은 [projectId]와 [category]가 없다. */
data class CalendarEntry(
    val source: CalendarSource,
    val eventId: Long,
    val projectId: Long?,
    val createdBy: Long,
    val title: String,
    val description: String?,
    val allDay: Boolean,
    val timeZone: String,
    val series: CalendarSeries,
    val category: CalendarCategory?,
    val attendeeUserIds: List<Long>,
    val reminderMinutes: List<Int>,
)

data class CalendarItem(val entry: CalendarEntry, val occurrence: CalendarOccurrence)

/**
 * 팀 캘린더 조회 필터. 필터끼리는 모두 만족해야 하고, 한 필터의 값 중 하나만 맞으면 된다. 빈 필터는 거르지 않는다.
 * [teamOnly]는 `projectIds`의 `teamOnly` 값으로, 프로젝트에 속하지 않는 팀 일정을 고른다.
 */
data class TeamCalendarFilter(
    val projectIds: Set<Long> = emptySet(),
    val teamOnly: Boolean = false,
    val categoryIds: Set<Long> = emptySet(),
    val attendeeUserIds: Set<Long> = emptySet(),
) {
    fun matches(entry: CalendarEntry): Boolean =
        matchesProject(entry) && matchesCategory(entry) && matchesAttendee(entry)

    private fun matchesProject(entry: CalendarEntry): Boolean {
        if (projectIds.isEmpty() && !teamOnly) return true
        return when (entry.source) {
            CalendarSource.TEAM -> teamOnly
            CalendarSource.PROJECT -> entry.projectId in projectIds
        }
    }

    /** 카테고리는 프로젝트 일정에만 있으므로 카테고리 필터를 주면 팀 일정은 빠진다. */
    private fun matchesCategory(entry: CalendarEntry): Boolean =
        categoryIds.isEmpty() || entry.category?.id in categoryIds

    private fun matchesAttendee(entry: CalendarEntry): Boolean =
        attendeeUserIds.isEmpty() || entry.attendeeUserIds.any { it in attendeeUserIds }

    companion object {
        const val TEAM_ONLY = "teamOnly"

        /** 양수 ID나 [TEAM_ONLY]가 아닌 프로젝트 값이 있으면 `null`이다. */
        fun of(
            projectIds: Collection<String>?,
            categoryIds: Collection<Long>?,
            attendeeUserIds: Collection<Long>?,
        ): TeamCalendarFilter? {
            val values = projectIds.orEmpty().map { it.trim() }
            val ids = values.filter {
                it != TEAM_ONLY
            }.map { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: return null }
            return TeamCalendarFilter(
                projectIds = ids.toSet(),
                teamOnly = TEAM_ONLY in values,
                categoryIds = categoryIds.orEmpty().toSet(),
                attendeeUserIds = attendeeUserIds.orEmpty().toSet(),
            )
        }
    }
}

object TeamCalendarMerger {
    /**
     * 팀 일정과 프로젝트 일정을 펼쳐 합친다. 프로젝트 일정은 요청자가 읽을 수 있는 [readableProjectIds]에 든 것만 넣는다.
     * 결과는 시작 시각, 출처, 일정 ID, 원래 시작 시각 순이다.
     */
    fun merge(
        entries: Collection<CalendarEntry>,
        readableProjectIds: Set<Long>,
        filter: TeamCalendarFilter,
        from: Instant,
        to: Instant,
    ): List<CalendarItem> = entries
        .filter { isVisible(it, readableProjectIds) && filter.matches(it) }
        .flatMap { entry -> CalendarRecurrenceExpander.expand(entry.series, from, to).map { CalendarItem(entry, it) } }
        .sortedWith(
            compareBy(
                { it.occurrence.startAt },
                { it.entry.source },
                { it.entry.eventId },
                { it.occurrence.originalStartAt },
            ),
        )

    /** 팀 일정은 팀 멤버 모두가 본다. 비공개 프로젝트를 포함한 프로젝트 일정은 그 프로젝트를 읽을 수 있을 때만 본다. */
    fun isVisible(entry: CalendarEntry, readableProjectIds: Set<Long>): Boolean = when (entry.source) {
        CalendarSource.TEAM -> true
        CalendarSource.PROJECT -> entry.projectId in readableProjectIds
    }
}
