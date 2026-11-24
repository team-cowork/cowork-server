package com.cowork.team.domain.calendar.projection

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface ProjectCalendarEventProjectionRepository : JpaRepository<ProjectCalendarEventProjection, Long> {
    @Query(
        "SELECT e FROM ProjectCalendarEventProjection e WHERE e.teamId = :teamId AND e.deleted = false " +
            "AND e.projectId IN :projectIds AND e.rangeStartAt < :to " +
            "AND (e.rangeEndAt IS NULL OR e.rangeEndAt >= :from) ORDER BY e.eventId",
    )
    fun findActiveInRange(
        @Param("teamId") teamId: Long,
        @Param("projectIds") projectIds: Collection<Long>,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<ProjectCalendarEventProjection>
}

interface ProjectReaderStateProjectionRepository : JpaRepository<ProjectReaderStateProjection, Long> {
    @Query(
        "SELECT s.projectId FROM ProjectReaderStateProjection s JOIN s.userIds u " +
            "WHERE s.teamId = :teamId AND s.deleted = false AND u = :userId",
    )
    fun findReadableProjectIds(@Param("teamId") teamId: Long, @Param("userId") userId: Long): List<Long>
}
