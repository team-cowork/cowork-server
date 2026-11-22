package com.cowork.project.domain.calendar.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * 반복 규칙. 발생 시각은 일정의 시간대에서 현지 시각으로 계산하므로 일광 절약 시간이 바뀌어도 현지 시작 시각을 지킨다.
 * [until]은 마지막으로 허용하는 발생분의 시작 시각이고, [count]는 첫 발생분부터 센 전체 발생 수다.
 * 예외로 취소한 발생분도 [count]에 포함한다.
 */
data class CalendarRecurrence(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val until: Instant? = null,
    val count: Int? = null,
) {
    init {
        require(interval in 1..MAX_INTERVAL) { "recurrence.interval must be between 1 and $MAX_INTERVAL" }
        require(until == null || count == null) { "recurrence.until and recurrence.count are exclusive" }
        require(count == null || count in 1..MAX_COUNT) { "recurrence.count must be between 1 and $MAX_COUNT" }
        require(byDay.isEmpty() || frequency == RecurrenceFrequency.WEEKLY) {
            "recurrence.byDay is allowed only for WEEKLY"
        }
    }

    companion object {
        const val MAX_INTERVAL = 99
        const val MAX_COUNT = 1000
    }
}

/**
 * 반복 일정의 발생분 하나에 대한 예외. [originalStartAt]은 규칙이 만든 원래 시작 시각이다.
 * [description]이 `null`이면 원본 설명을 따르고, 빈 문자열이면 그 발생분의 설명을 비운다.
 */
data class CalendarOccurrenceException(
    val originalStartAt: Instant,
    val cancelled: Boolean = false,
    val title: String? = null,
    val description: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
)

/** 펼칠 일정 원본. 반복이 없으면 [startAt]의 발생분 하나만 있다. */
data class CalendarSeries(
    val startAt: Instant,
    val endAt: Instant,
    val zone: ZoneId,
    val recurrence: CalendarRecurrence? = null,
    val exceptions: List<CalendarOccurrenceException> = emptyList(),
) {
    init {
        require(!endAt.isBefore(startAt)) { "endAt must not be before startAt" }
    }

    val duration: Duration get() = Duration.between(startAt, endAt)
}

/**
 * 조회 기간에 들어온 발생분. 예외로 바꾼 값이 있으면 [title]과 [description]에 담는다.
 * [description]은 예외 값 그대로다. 응답할 설명은 [resolveDescription]으로 구한다.
 */
data class CalendarOccurrence(
    val originalStartAt: Instant,
    val startAt: Instant,
    val endAt: Instant,
    val title: String? = null,
    val description: String? = null,
) {
    /** 예외 설명이 `null`이면 [original]을 쓰고, 빈 문자열이면 비운 설명이므로 `null`을 돌려준다. */
    fun resolveDescription(original: String?): String? =
        if (description == null) original else description.ifEmpty { null }
}

object CalendarRecurrenceExpander {
    /** 규칙 하나가 만들 수 있는 후보 발생분 수의 상한. 잘못된 규칙이 무한히 돌지 않게 막는다. */
    private const val MAX_SCANNED = 200_000

    /** 원래 시작 시각을 오름차순으로 만든다. [until]과 [count]를 적용한다. */
    fun originalStarts(series: CalendarSeries): Sequence<Instant> {
        val recurrence = series.recurrence ?: return sequenceOf(series.startAt)
        val start = series.startAt.atZone(series.zone).toLocalDateTime()
        val candidates = when (recurrence.frequency) {
            RecurrenceFrequency.DAILY -> generateSequence(0L) { it + 1 }
                .map { start.plusDays(it * recurrence.interval) }

            RecurrenceFrequency.MONTHLY -> generateSequence(0L) { it + 1 }
                .map { start.plusMonths(it * recurrence.interval) }
                // 31일처럼 그 달에 없는 날은 건너뛴다. plusMonths는 말일로 당기므로 일자가 다르면 버린다.
                .filter { it.dayOfMonth == start.dayOfMonth }

            RecurrenceFrequency.WEEKLY -> weeklyCandidates(start, recurrence)
        }
        val starts = candidates.take(MAX_SCANNED)
            .map { ZonedDateTime.of(it, series.zone).toInstant() }
            .filter { !it.isBefore(series.startAt) }
            .takeWhile { recurrence.until == null || !it.isAfter(recurrence.until) }
        return recurrence.count?.let { starts.take(it) } ?: starts
    }

    private fun weeklyCandidates(start: LocalDateTime, recurrence: CalendarRecurrence): Sequence<LocalDateTime> {
        val days = recurrence.byDay.ifEmpty { setOf(start.dayOfWeek) }.sorted()
        val firstWeek = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return generateSequence(0L) { it + 1 }.flatMap { week ->
            val monday = firstWeek.plusWeeks(week * recurrence.interval)
            days.asSequence().map { day -> monday.with(TemporalAdjusters.nextOrSame(day)).atTime(start.toLocalTime()) }
        }
    }

    /** [from] 이상 [to] 미만 기간과 겹치는 발생분을 시작 시각 순으로 돌려준다. 취소한 발생분은 뺀다. */
    fun expand(series: CalendarSeries, from: Instant, to: Instant): List<CalendarOccurrence> {
        require(from.isBefore(to)) { "from must be before to" }
        val exceptions = series.exceptions.associateBy { it.originalStartAt }
        val duration = series.duration
        val result = mutableListOf<CalendarOccurrence>()
        val visited = mutableSetOf<Instant>()
        for (originalStart in originalStarts(series).takeWhile { it.isBefore(to) }) {
            visited += originalStart
            occurrence(originalStart, duration, exceptions[originalStart])
                ?.takeIf { overlaps(it, from, to) }
                ?.let(result::add)
        }
        // 예외로 옮긴 발생분은 원래 시각이 기간 밖이어도 옮긴 시각이 기간과 겹치면 넣는다.
        series.exceptions
            .filter { !it.cancelled && it.originalStartAt !in visited }
            .mapNotNull { occurrence(it.originalStartAt, duration, it) }
            .filter { overlaps(it, from, to) && isOccurrence(series, it.originalStartAt) }
            .forEach(result::add)
        return result.sortedWith(compareBy({ it.startAt }, { it.originalStartAt }))
    }

    /** [originalStartAt]이 규칙이 만드는 발생분인지 확인한다. */
    fun isOccurrence(series: CalendarSeries, originalStartAt: Instant): Boolean =
        originalStarts(series).takeWhile { !it.isAfter(originalStartAt) }.any { it == originalStartAt }

    /** [before]보다 먼저 시작하는 원래 발생분 수. "이후 모두" 범위로 시리즈를 나눌 때 쓴다. */
    fun countBefore(series: CalendarSeries, before: Instant): Int =
        originalStarts(series).takeWhile { it.isBefore(before) }.count()

    /**
     * 시리즈가 차지하는 전체 기간. 끝이 없는 반복이면 끝은 `null`이다.
     * 기간 조회에서 후보 일정을 좁히는 데 쓰며, 예외로 옮긴 발생분도 포함한다.
     */
    fun bounds(series: CalendarSeries): Pair<Instant, Instant?> {
        val moved = series.exceptions.filter { !it.cancelled && it.startAt != null && it.endAt != null }
        val start = (moved.mapNotNull { it.startAt } + series.startAt).min()
        val recurrence = series.recurrence
        if (recurrence != null && recurrence.until == null && recurrence.count == null) return start to null
        val lastStart = originalStarts(series).lastOrNull() ?: series.startAt
        val end = (moved.mapNotNull { it.endAt } + lastStart.plus(series.duration)).max()
        return start to end
    }

    private fun occurrence(
        originalStart: Instant,
        duration: Duration,
        exception: CalendarOccurrenceException?,
    ): CalendarOccurrence? {
        if (exception?.cancelled == true) return null
        val start = exception?.startAt ?: originalStart
        val end = exception?.endAt ?: start.plus(duration)
        return CalendarOccurrence(originalStart, start, end, exception?.title, exception?.description)
    }

    private fun overlaps(occurrence: CalendarOccurrence, from: Instant, to: Instant): Boolean =
        if (occurrence.startAt == occurrence.endAt) {
            !occurrence.startAt.isBefore(from) && occurrence.startAt.isBefore(to)
        } else {
            occurrence.startAt.isBefore(to) && occurrence.endAt.isAfter(from)
        }
}

/** "이후 모두" 범위 수정과 삭제에서 원본 시리즈를 끊는 규칙. */
object CalendarSeriesSplitter {
    data class Split(
        /** 원본에 남길 규칙. */
        val head: CalendarRecurrence,
        /** 새 시리즈에 줄 규칙. 원본의 종료 조건을 남은 발생분에 맞춘다. */
        val tail: CalendarRecurrence,
        /** 원본에 남길 예외. 끊는 시각 이전의 예외만 남긴다. */
        val headExceptions: List<CalendarOccurrenceException>,
        /** 끊는 시각보다 뒤의 예외. 끊는 발생분은 이번 수정이 정하므로 넣지 않는다. */
        val tailExceptions: List<CalendarOccurrenceException>,
    )

    /**
     * [occurrenceStartAt]부터 시리즈를 나눈다. 첫 발생분이면 나눌 것이 없으므로 `null`을 돌려주고,
     * 호출자는 전체 범위로 처리한다.
     */
    fun split(series: CalendarSeries, occurrenceStartAt: Instant): Split? {
        val recurrence = requireNotNull(series.recurrence) { "Only recurring events can be split" }
        require(CalendarRecurrenceExpander.isOccurrence(series, occurrenceStartAt)) {
            "occurrenceStartAt is not an occurrence of the event"
        }
        val before = CalendarRecurrenceExpander.countBefore(series, occurrenceStartAt)
        if (before == 0) return null
        val head = if (recurrence.count != null) {
            recurrence.copy(count = before)
        } else {
            recurrence.copy(until = occurrenceStartAt.minus(1, ChronoUnit.MICROS))
        }
        val tail = recurrence.copy(count = recurrence.count?.let { it - before })
        return Split(
            head,
            tail,
            headExceptions = series.exceptions.filter { it.originalStartAt.isBefore(occurrenceStartAt) },
            tailExceptions = series.exceptions.filter { it.originalStartAt.isAfter(occurrenceStartAt) },
        )
    }
}
