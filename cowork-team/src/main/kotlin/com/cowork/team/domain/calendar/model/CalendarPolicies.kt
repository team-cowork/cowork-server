package com.cowork.team.domain.calendar.model

import java.time.Duration
import java.time.Instant

enum class CalendarEditScope { THIS, FOLLOWING, ALL }

enum class CalendarAction { UPDATE, DELETE }

/** 일정 수정·삭제 권한 판정. 작성자는 팀 권한 없이 자기 것을 고친다. */
object CalendarPermissionPolicy {
    fun requiresTeamPermission(createdBy: Long, userId: Long): Boolean = createdBy != userId

    fun allows(createdBy: Long, userId: Long, hasTeamPermission: () -> Boolean): Boolean =
        !requiresTeamPermission(createdBy, userId) || hasTeamPermission()
}

/** 일정 리마인더 규칙. */
object CalendarReminderPolicy {
    val ALLOWED_MINUTES: Set<Int> = linkedSetOf(0, 10, 30, 60, 1440)
    const val MAX_REMINDERS = 2
    val MAX_LEAD: Duration = Duration.ofMinutes(ALLOWED_MINUTES.max().toLong())

    /** 중복을 없애고 오름차순으로 정렬한다. 허용하지 않는 값이나 개수를 넘으면 `null`이다. */
    fun normalize(minutes: Collection<Int>?): List<Int>? {
        val distinct = minutes.orEmpty().toSortedSet().toList()
        if (distinct.size > MAX_REMINDERS || distinct.any { it !in ALLOWED_MINUTES }) return null
        return distinct
    }

    /** 참석자가 받고, 참석자가 없으면 작성자가 받는다. 지금 팀 멤버가 아닌 사용자는 뺀다. */
    fun recipients(createdBy: Long, attendeeUserIds: Collection<Long>, members: Set<Long>): List<Long> =
        attendeeUserIds.ifEmpty { listOf(createdBy) }.distinct().filter { it in members }.sorted()

    data class DueReminder(val occurrence: CalendarOccurrence, val minutesBefore: Int) {
        val fireAt: Instant get() = occurrence.startAt.minus(Duration.ofMinutes(minutesBefore.toLong()))
    }

    /**
     * [after] 초과 [now] 이하에 보낼 시각이 온 리마인더를 찾는다. 예외로 옮긴 발생분은 옮긴 시작 시각을 기준으로 한다.
     * 스케줄러가 잠시 멈췄어도 [after]까지 거슬러 보내며, 이미 보낸 것은 호출자가 걸러낸다.
     */
    fun due(series: CalendarSeries, minutes: List<Int>, after: Instant, now: Instant): List<DueReminder> {
        if (minutes.isEmpty() || !after.isBefore(now)) return emptyList()
        val lead = Duration.ofMinutes(minutes.max().toLong())
        return CalendarRecurrenceExpander.expand(series, after, now.plus(lead).plusNanos(1))
            .flatMap { occurrence -> minutes.map { DueReminder(occurrence, it) } }
            .filter { it.fireAt.isAfter(after) && !it.fireAt.isAfter(now) }
    }
}

/** 기간 조회 규칙. */
object CalendarRangePolicy {
    val MAX_RANGE: Duration = Duration.ofDays(93)

    fun isValid(from: Instant, to: Instant): Boolean =
        from.isBefore(to) && !Duration.between(from, to).minus(MAX_RANGE).isPositive
}
