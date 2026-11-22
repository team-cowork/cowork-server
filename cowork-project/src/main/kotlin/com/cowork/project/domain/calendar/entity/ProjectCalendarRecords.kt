package com.cowork.project.domain.calendar.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 발생분과 리마인더 값마다 한 번만 보내도록 남기는 발송 기록. */
@Entity
@Table(name = "tb_project_calendar_reminder_deliveries")
class ProjectCalendarReminderDelivery(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "original_start_at", nullable = false)
    val originalStartAt: Instant,

    @Column(name = "minutes_before", nullable = false)
    val minutesBefore: Int,

    @Column(name = "delivered_at", nullable = false)
    val deliveredAt: Instant,
)

/** 리마인더를 마지막으로 확인한 시각. 한 행만 있다. */
@Entity
@Table(name = "tb_project_calendar_reminder_cursor")
class ProjectCalendarReminderCursor(
    @Id
    val id: Long = SINGLETON_ID,

    @Column(name = "checked_at", nullable = false)
    var checkedAt: Instant,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/** project.readable-user.state.v1로 마지막에 발행한 프로젝트별 읽기 가능 사용자 목록. */
@Entity
@Table(name = "tb_project_readable_user_states")
class ProjectReadableUserState(
    @Id
    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "user_ids", nullable = false, columnDefinition = "TEXT")
    var userIds: String,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    val userIdList: List<Long> get() = userIds.split(',').filter { it.isNotBlank() }.map(String::toLong)

    companion object {
        fun encode(userIds: Collection<Long>): String = userIds.sorted().joinToString(",")
    }
}
