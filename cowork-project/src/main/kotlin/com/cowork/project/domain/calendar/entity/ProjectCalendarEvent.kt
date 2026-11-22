package com.cowork.project.domain.calendar.entity

import com.cowork.project.domain.calendar.model.CalendarOccurrenceException
import com.cowork.project.domain.calendar.model.CalendarRecurrence
import com.cowork.project.domain.calendar.model.CalendarRecurrenceExpander
import com.cowork.project.domain.calendar.model.CalendarSeries
import com.cowork.project.domain.calendar.model.RecurrenceFrequency
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

@Entity
@Table(name = "tb_project_calendar_events")
class ProjectCalendarEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(length = 2000)
    var description: String?,

    @Column(name = "start_at", nullable = false)
    var startAt: Instant,

    @Column(name = "end_at", nullable = false)
    var endAt: Instant,

    @Column(name = "all_day", nullable = false)
    var allDay: Boolean,

    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String,

    @Column(name = "category_id")
    var categoryId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_frequency", length = 10)
    var recurrenceFrequency: RecurrenceFrequency? = null,

    @Column(name = "recurrence_interval", nullable = false)
    var recurrenceInterval: Int = 1,

    @Column(name = "recurrence_by_day", length = 64)
    var recurrenceByDay: String? = null,

    @Column(name = "recurrence_until")
    var recurrenceUntil: Instant? = null,

    @Column(name = "recurrence_count")
    var recurrenceCount: Int? = null,

    @Column(name = "reminder_minutes", nullable = false, length = 32)
    var reminderMinutes: String = "",

    @Column(name = "range_start_at", nullable = false)
    var rangeStartAt: Instant,

    @Column(name = "range_end_at")
    var rangeEndAt: Instant? = null,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant = Instant.EPOCH,

    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "tb_project_calendar_event_attendees", joinColumns = [JoinColumn(name = "event_id")])
    @Column(name = "user_id", nullable = false)
    var attendeeUserIds: MutableSet<Long> = linkedSetOf(),

    /**
     * 원래 시작 시각마다 예외는 하나다. 테이블 PK `(event_id, original_start_at)`와 같은 키로 매핑해
     * 같은 발생분의 예외가 둘 생기지 않게 하고, 행을 고치거나 지울 때도 nullable 컬럼이 아니라 키로 찾게 한다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "tb_project_calendar_event_exceptions", joinColumns = [JoinColumn(name = "event_id")])
    @MapKeyColumn(name = "original_start_at")
    var exceptions: MutableMap<Instant, ProjectCalendarEventException> = linkedMapOf(),
) {
    val zone: ZoneId get() = ZoneId.of(timeZone)

    val recurrence: CalendarRecurrence?
        get() = recurrenceFrequency?.let { frequency ->
            CalendarRecurrence(
                frequency,
                recurrenceInterval,
                recurrenceByDay.orEmpty().split(',').filter { it.isNotBlank() }.map(DayOfWeek::valueOf).toSet(),
                recurrenceUntil,
                recurrenceCount,
            )
        }

    val reminders: List<Int>
        get() = reminderMinutes.split(',').filter { it.isNotBlank() }.map(String::toInt)

    fun series(): CalendarSeries =
        CalendarSeries(startAt, endAt, zone, recurrence, exceptions.map { (start, value) -> value.toModel(start) })

    fun exceptionModels(): List<CalendarOccurrenceException> =
        exceptions.entries.sortedBy { it.key }.map { (start, value) -> value.toModel(start) }

    fun applyRecurrence(recurrence: CalendarRecurrence?) {
        recurrenceFrequency = recurrence?.frequency
        recurrenceInterval = recurrence?.interval ?: 1
        recurrenceByDay = recurrence?.byDay?.sorted()?.joinToString(",")?.takeIf { it.isNotEmpty() }
        recurrenceUntil = recurrence?.until
        recurrenceCount = recurrence?.count
    }

    fun applyReminders(minutes: List<Int>) {
        reminderMinutes = minutes.joinToString(",")
    }

    fun replaceExceptions(models: List<CalendarOccurrenceException>) {
        exceptions.clear()
        models.forEach(::putException)
    }

    fun putException(model: CalendarOccurrenceException) {
        exceptions[model.originalStartAt] = ProjectCalendarEventException.from(model)
    }

    /** 조회 후보를 좁히는 전체 기간을 원본과 예외로 다시 계산한다. 원본 필드를 바꾼 뒤에 부른다. */
    fun refreshRange() {
        val (start, end) = CalendarRecurrenceExpander.bounds(series())
        rangeStartAt = start
        rangeEndAt = end
    }
}

/** 발생분 하나의 예외 값. 원래 시작 시각은 [ProjectCalendarEvent.exceptions]의 키다. */
@Embeddable
class ProjectCalendarEventException(
    @Column(nullable = false)
    var cancelled: Boolean = false,

    @Column(length = 200)
    var title: String? = null,

    @Column(length = 2000)
    var description: String? = null,

    @Column(name = "start_at")
    var startAt: Instant? = null,

    @Column(name = "end_at")
    var endAt: Instant? = null,
) {
    fun toModel(originalStartAt: Instant) =
        CalendarOccurrenceException(originalStartAt, cancelled, title, description, startAt, endAt)

    companion object {
        fun from(model: CalendarOccurrenceException) = ProjectCalendarEventException(
            model.cancelled,
            model.title,
            model.description,
            model.startAt,
            model.endAt,
        )
    }
}
