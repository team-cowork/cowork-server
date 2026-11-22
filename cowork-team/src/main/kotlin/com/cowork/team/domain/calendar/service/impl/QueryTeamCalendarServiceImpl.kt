package com.cowork.team.domain.calendar.service.impl

import com.cowork.team.domain.calendar.entity.TeamCalendarEvent
import com.cowork.team.domain.calendar.model.CalendarEntry
import com.cowork.team.domain.calendar.model.CalendarRangePolicy
import com.cowork.team.domain.calendar.model.CalendarSource
import com.cowork.team.domain.calendar.model.TeamCalendarFilter
import com.cowork.team.domain.calendar.model.TeamCalendarMerger
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarEventResDto
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarOccurrenceResDto
import com.cowork.team.domain.calendar.projection.ProjectCalendarProjectionStore
import com.cowork.team.domain.calendar.repository.TeamCalendarEventRepository
import com.cowork.team.domain.calendar.service.QueryTeamCalendarService
import com.cowork.team.domain.calendar.service.TeamCalendarAccessGuard
import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

@Service
class QueryTeamCalendarServiceImpl(
    private val teamCalendarAccessGuard: TeamCalendarAccessGuard,
    private val teamCalendarEventRepository: TeamCalendarEventRepository,
    private val projectCalendarProjectionStore: ProjectCalendarProjectionStore,
    private val projectionReadinessGate: ProjectionReadinessGate,
) : QueryTeamCalendarService {

    @Transactional(readOnly = true)
    override fun get(userId: Long, teamId: Long, eventId: Long): TeamCalendarEventResDto {
        teamCalendarAccessGuard.requireMember(teamId, userId)
        val event = teamCalendarEventRepository.findByIdAndTeamId(eventId, teamId)
            ?: throw ExpectedException("일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return TeamCalendarEventResDto.of(event)
    }

    @Transactional(readOnly = true)
    override fun query(
        userId: Long,
        teamId: Long,
        from: Instant,
        to: Instant,
        filter: TeamCalendarFilter,
    ): List<TeamCalendarOccurrenceResDto> {
        if (!CalendarRangePolicy.isValid(from, to)) {
            throw ExpectedException("조회 기간은 시작이 끝보다 앞서고 최대 93일이어야 합니다.", HttpStatus.BAD_REQUEST)
        }
        teamCalendarAccessGuard.requireMember(teamId, userId)
        projectionReadinessGate.requireCalendarReady()
        val readableProjectIds = projectCalendarProjectionStore.readableProjectIds(teamId, userId)
        val projectIds = if (filter.projectIds.isEmpty()) {
            readableProjectIds
        } else {
            readableProjectIds.intersect(filter.projectIds)
        }
        val entries = teamCalendarEventRepository.findInRange(teamId, from, to).map(::toEntry) +
            projectCalendarProjectionStore.findEntries(teamId, projectIds, from, to)
        return TeamCalendarMerger.merge(entries, readableProjectIds, filter, from, to)
            .map(TeamCalendarOccurrenceResDto::of)
    }

    private fun toEntry(event: TeamCalendarEvent) = CalendarEntry(
        source = CalendarSource.TEAM,
        eventId = event.id,
        projectId = null,
        createdBy = event.createdBy,
        title = event.title,
        description = event.description,
        allDay = event.allDay,
        timeZone = event.timeZone,
        series = event.series(),
        category = null,
        attendeeUserIds = event.attendeeUserIds.sorted(),
        reminderMinutes = event.reminders,
    )
}
