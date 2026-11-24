package com.cowork.team.domain.calendar.service

import com.cowork.team.domain.calendar.model.CalendarEditScope
import com.cowork.team.domain.calendar.model.TeamCalendarFilter
import com.cowork.team.domain.calendar.presentation.data.request.SaveTeamCalendarEventReqDto
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarEventResDto
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarOccurrenceResDto
import java.time.Instant

interface ManageTeamCalendarEventService {
    fun create(userId: Long, teamId: Long, request: SaveTeamCalendarEventReqDto): TeamCalendarEventResDto

    /** 바뀐 원본을 돌려준다. "이후 모두"로 나누면 새로 만든 일정을 돌려준다. */
    fun update(
        userId: Long,
        teamId: Long,
        eventId: Long,
        scope: CalendarEditScope,
        occurrenceStartAt: Instant?,
        request: SaveTeamCalendarEventReqDto,
    ): TeamCalendarEventResDto

    fun delete(userId: Long, teamId: Long, eventId: Long, scope: CalendarEditScope, occurrenceStartAt: Instant?)
}

interface QueryTeamCalendarService {
    fun get(userId: Long, teamId: Long, eventId: Long): TeamCalendarEventResDto

    /** 팀 일정과 요청자가 읽을 수 있는 프로젝트의 일정을 합쳐 기간 안의 발생분으로 돌려준다. */
    fun query(
        userId: Long,
        teamId: Long,
        from: Instant,
        to: Instant,
        filter: TeamCalendarFilter,
    ): List<TeamCalendarOccurrenceResDto>
}
