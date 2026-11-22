package com.cowork.project.domain.calendar.repository

import com.cowork.project.domain.calendar.entity.ProjectCalendarCategory
import com.cowork.project.domain.calendar.entity.ProjectCalendarEvent
import com.cowork.project.domain.calendar.entity.ProjectCalendarReminderCursor
import com.cowork.project.domain.calendar.entity.ProjectCalendarReminderDelivery
import com.cowork.project.domain.calendar.entity.ProjectReadableUserState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ProjectCalendarEventRepository : JpaRepository<ProjectCalendarEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProjectCalendarEvent e WHERE e.id = :id AND e.projectId = :projectId AND e.deleted = false")
    fun findActiveForUpdate(@Param("id") id: Long, @Param("projectId") projectId: Long): ProjectCalendarEvent?

    @Query(
        "SELECT e FROM ProjectCalendarEvent e WHERE e.projectId = :projectId AND e.deleted = false " +
            "AND e.rangeStartAt < :to AND (e.rangeEndAt IS NULL OR e.rangeEndAt >= :from) ORDER BY e.id",
    )
    fun findActiveInRange(
        @Param("projectId") projectId: Long,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<ProjectCalendarEvent>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProjectCalendarEvent e WHERE e.projectId IN :projectIds AND e.deleted = false ORDER BY e.id")
    fun findActiveByProjectIdsForUpdate(@Param("projectIds") projectIds: Collection<Long>): List<ProjectCalendarEvent>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProjectCalendarEvent e WHERE e.categoryId = :categoryId AND e.deleted = false ORDER BY e.id")
    fun findActiveByCategoryIdForUpdate(@Param("categoryId") categoryId: Long): List<ProjectCalendarEvent>

    /** 리마인더가 있고 [from] 이후에 발생분이 남은 일정. 시작이 [to] 이후인 일정은 아직 알릴 때가 아니다. */
    @Query(
        "SELECT e FROM ProjectCalendarEvent e WHERE e.deleted = false AND e.reminderMinutes <> '' " +
            "AND e.rangeStartAt < :to AND (e.rangeEndAt IS NULL OR e.rangeEndAt >= :from) ORDER BY e.id",
    )
    fun findReminderCandidates(@Param("from") from: Instant, @Param("to") to: Instant): List<ProjectCalendarEvent>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProjectCalendarEvent e WHERE e.id > :afterId ORDER BY e.id")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ProjectCalendarEvent>
}

interface ProjectCalendarCategoryRepository : JpaRepository<ProjectCalendarCategory, Long> {
    fun findAllByProjectIdOrderByIdAsc(projectId: Long): List<ProjectCalendarCategory>

    fun findByIdAndProjectId(id: Long, projectId: Long): ProjectCalendarCategory?

    fun existsByProjectIdAndName(projectId: Long, name: String): Boolean

    fun existsByProjectIdAndNameAndIdNot(projectId: Long, name: String, id: Long): Boolean

    @Modifying
    @Query("DELETE FROM ProjectCalendarCategory c WHERE c.projectId IN :projectIds")
    fun deleteAllByProjectIds(@Param("projectIds") projectIds: Collection<Long>)
}

interface ProjectCalendarReminderDeliveryRepository : JpaRepository<ProjectCalendarReminderDelivery, Long> {
    fun existsByEventIdAndOriginalStartAtAndStartAtAndMinutesBefore(
        eventId: Long,
        originalStartAt: Instant,
        startAt: Instant,
        minutesBefore: Int,
    ): Boolean

    @Modifying
    @Query("DELETE FROM ProjectCalendarReminderDelivery d WHERE d.deliveredAt < :before")
    fun deleteDeliveredBefore(@Param("before") before: Instant): Int
}

interface ProjectCalendarReminderCursorRepository : JpaRepository<ProjectCalendarReminderCursor, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ProjectCalendarReminderCursor c WHERE c.id = :id")
    fun findForUpdate(@Param("id") id: Long): ProjectCalendarReminderCursor?
}

interface ProjectReadableUserStateRepository : JpaRepository<ProjectReadableUserState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProjectReadableUserState s WHERE s.projectId = :projectId")
    fun findForUpdate(@Param("projectId") projectId: Long): ProjectReadableUserState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT s FROM ProjectReadableUserState s WHERE s.deleted = true AND s.projectId > :afterId ORDER BY s.projectId",
    )
    fun findDeletedSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ProjectReadableUserState>
}
