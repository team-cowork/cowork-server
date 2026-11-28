package com.cowork.team.domain.calendar.repository

import com.cowork.team.domain.calendar.entity.TeamCalendarEvent
import com.cowork.team.domain.calendar.entity.TeamCalendarReminderCursor
import com.cowork.team.domain.calendar.entity.TeamCalendarReminderDelivery
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface TeamCalendarEventRepository : JpaRepository<TeamCalendarEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM TeamCalendarEvent e WHERE e.id = :id AND e.teamId = :teamId")
    fun findForUpdate(@Param("id") id: Long, @Param("teamId") teamId: Long): TeamCalendarEvent?

    fun findByIdAndTeamId(id: Long, teamId: Long): TeamCalendarEvent?

    @Query(
        "SELECT e FROM TeamCalendarEvent e WHERE e.teamId = :teamId " +
            "AND e.rangeStartAt < :to AND (e.rangeEndAt IS NULL OR e.rangeEndAt >= :from) ORDER BY e.id",
    )
    fun findInRange(
        @Param("teamId") teamId: Long,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<TeamCalendarEvent>

    /** 리마인더가 있고 [from] 이후에 발생분이 남은 일정. 시작이 [to] 이후인 일정은 아직 알릴 때가 아니다. */
    @Query(
        "SELECT e FROM TeamCalendarEvent e WHERE e.reminderMinutes <> '' " +
            "AND e.rangeStartAt < :to AND (e.rangeEndAt IS NULL OR e.rangeEndAt >= :from) ORDER BY e.id",
    )
    fun findReminderCandidates(@Param("from") from: Instant, @Param("to") to: Instant): List<TeamCalendarEvent>

    fun findAllByTeamId(teamId: Long): List<TeamCalendarEvent>
}

interface TeamCalendarReminderDeliveryRepository : JpaRepository<TeamCalendarReminderDelivery, Long> {
    fun existsByEventIdAndOriginalStartAtAndStartAtAndMinutesBefore(
        eventId: Long,
        originalStartAt: Instant,
        startAt: Instant,
        minutesBefore: Int,
    ): Boolean

    @Modifying
    @Query("DELETE FROM TeamCalendarReminderDelivery d WHERE d.deliveredAt < :before")
    fun deleteDeliveredBefore(@Param("before") before: Instant): Int
}

interface TeamCalendarReminderCursorRepository : JpaRepository<TeamCalendarReminderCursor, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM TeamCalendarReminderCursor c WHERE c.id = :id")
    fun findForUpdate(@Param("id") id: Long): TeamCalendarReminderCursor?
}
