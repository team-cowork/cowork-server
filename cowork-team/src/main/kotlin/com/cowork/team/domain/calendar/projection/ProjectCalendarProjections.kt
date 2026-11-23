package com.cowork.team.domain.calendar.projection

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant

/**
 * `project.calendar-event.state.v1`의 일정 원본과 예외. 반복은 조회할 때 펼친다.
 * 삭제는 [deleted]로 남겨 늦게 온 옛 버전이 일정을 되살리지 않게 한다.
 */
@Entity
@Table(name = "tb_team_calendar_project_event_projections")
class ProjectCalendarEventProjection(
    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "project_id", nullable = false)
    var projectId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long = 0,

    @Column(nullable = false, length = 200)
    var title: String = "",

    @Column(length = 2000)
    var description: String? = null,

    @Column(name = "start_at", nullable = false)
    var startAt: Instant = Instant.EPOCH,

    @Column(name = "end_at", nullable = false)
    var endAt: Instant = Instant.EPOCH,

    @Column(name = "all_day", nullable = false)
    var allDay: Boolean = false,

    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String = "UTC",

    /** `ProjectCalendarEventStatePayload.recurrence`의 JSON. 반복하지 않으면 `null`이다. */
    @Column(name = "recurrence_json", columnDefinition = "TEXT")
    var recurrenceJson: String? = null,

    /** `ProjectCalendarEventStatePayload.exceptions`의 JSON 배열. */
    @Column(name = "exceptions_json", nullable = false, columnDefinition = "TEXT")
    var exceptionsJson: String = "[]",

    @Column(name = "category_id")
    var categoryId: Long? = null,

    @Column(name = "category_name", length = 50)
    var categoryName: String? = null,

    @Column(name = "category_color_hex", length = 7)
    var categoryColorHex: String? = null,

    @Column(name = "attendee_user_ids", nullable = false, columnDefinition = "TEXT")
    var attendeeUserIds: String = "",

    @Column(name = "reminder_minutes", nullable = false, length = 32)
    var reminderMinutes: String = "",

    @Column(name = "range_start_at", nullable = false)
    var rangeStartAt: Instant = Instant.EPOCH,

    @Column(name = "range_end_at")
    var rangeEndAt: Instant? = null,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
)

/** `project.readable-user.state.v1`의 프로젝트별 읽기 가능 사용자. 프로젝트를 지우면 [deleted]가 되고 목록은 빈다. */
@Entity
@Table(name = "tb_team_calendar_project_reader_states")
class ProjectReaderStateProjection(
    @Id
    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "tb_team_calendar_project_readers",
        joinColumns = [JoinColumn(name = "project_id")],
    )
    @Column(name = "user_id", nullable = false)
    var userIds: MutableSet<Long> = linkedSetOf(),
)
