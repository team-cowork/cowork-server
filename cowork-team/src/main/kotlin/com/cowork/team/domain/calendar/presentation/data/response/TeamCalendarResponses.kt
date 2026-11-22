package com.cowork.team.domain.calendar.presentation.data.response

import com.cowork.team.domain.calendar.entity.TeamCalendarEvent
import com.cowork.team.domain.calendar.model.CalendarCategory
import com.cowork.team.domain.calendar.model.CalendarItem
import com.cowork.team.domain.calendar.model.CalendarRecurrence
import com.cowork.team.domain.calendar.model.CalendarSource
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class TeamCalendarRecurrenceResDto(
    @field:Schema(description = "반복 주기", example = "WEEKLY")
    val frequency: String,
    @field:Schema(description = "반복 간격", example = "1")
    val interval: Int,
    @field:Schema(description = "WEEKLY에서 반복할 요일", example = "[\"MONDAY\"]")
    val byDay: List<String>,
    @field:Schema(description = "마지막 발생분이 시작할 수 있는 시각", nullable = true)
    val until: Instant?,
    @field:Schema(description = "전체 발생 수", nullable = true)
    val count: Int?,
) {
    companion object {
        fun of(recurrence: CalendarRecurrence) = TeamCalendarRecurrenceResDto(
            recurrence.frequency.name,
            recurrence.interval,
            recurrence.byDay.sorted().map { it.name },
            recurrence.until,
            recurrence.count,
        )
    }
}

data class TeamCalendarEventResDto(
    @field:Schema(description = "일정 ID", example = "1")
    val id: Long,
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "만든 사용자 ID", example = "7")
    val createdBy: Long,
    @field:Schema(description = "제목", example = "전체 회의")
    val title: String,
    @field:Schema(description = "설명", nullable = true)
    val description: String?,
    @field:Schema(description = "첫 발생분 시작 시각")
    val startAt: Instant,
    @field:Schema(description = "첫 발생분 종료 시각")
    val endAt: Instant,
    @field:Schema(description = "종일 일정 여부")
    val allDay: Boolean,
    @field:Schema(description = "IANA 시간대", example = "Asia/Seoul")
    val timeZone: String,
    @field:Schema(description = "참석자 사용자 ID")
    val attendeeUserIds: List<Long>,
    @field:Schema(description = "시작 몇 분 전에 알릴지", example = "[10]")
    val reminderMinutes: List<Int>,
    @field:Schema(description = "반복 규칙", nullable = true)
    val recurrence: TeamCalendarRecurrenceResDto?,
) {
    companion object {
        fun of(event: TeamCalendarEvent) = TeamCalendarEventResDto(
            id = event.id,
            teamId = event.teamId,
            createdBy = event.createdBy,
            title = event.title,
            description = event.description,
            startAt = event.startAt,
            endAt = event.endAt,
            allDay = event.allDay,
            timeZone = event.timeZone,
            attendeeUserIds = event.attendeeUserIds.sorted(),
            reminderMinutes = event.reminders,
            recurrence = event.recurrence?.let(TeamCalendarRecurrenceResDto::of),
        )
    }
}

data class TeamCalendarCategoryResDto(
    @field:Schema(description = "프로젝트 일정 카테고리 ID", example = "1")
    val id: Long,
    @field:Schema(description = "카테고리 이름", example = "회의")
    val name: String,
    @field:Schema(description = "색", example = "#4F46E5")
    val colorHex: String,
) {
    companion object {
        fun of(category: CalendarCategory) = TeamCalendarCategoryResDto(category.id, category.name, category.colorHex)
    }
}

data class TeamCalendarOccurrenceResDto(
    @field:Schema(description = "일정 출처. TEAM은 팀 일정, PROJECT는 프로젝트 일정입니다.", example = "TEAM")
    val source: CalendarSource,
    @field:Schema(description = "일정 ID. 출처마다 따로 매깁니다.", example = "1")
    val eventId: Long,
    @field:Schema(description = "프로젝트 ID. 팀 일정이면 없습니다.", nullable = true, example = "3")
    val projectId: Long?,
    @field:Schema(description = "반복 규칙이 만든 원래 시작 시각. 이 발생분만 수정하거나 삭제할 때 씁니다.")
    val originalStartAt: Instant,
    @field:Schema(description = "시작 시각")
    val startAt: Instant,
    @field:Schema(description = "종료 시각")
    val endAt: Instant,
    @field:Schema(description = "제목", example = "전체 회의")
    val title: String,
    @field:Schema(description = "설명", nullable = true)
    val description: String?,
    @field:Schema(description = "종일 일정 여부")
    val allDay: Boolean,
    @field:Schema(description = "IANA 시간대", example = "Asia/Seoul")
    val timeZone: String,
    @field:Schema(description = "반복 일정 여부")
    val recurring: Boolean,
    @field:Schema(description = "만든 사용자 ID", example = "7")
    val createdBy: Long,
    @field:Schema(description = "프로젝트 일정 카테고리. 팀 일정이면 없습니다.", nullable = true)
    val category: TeamCalendarCategoryResDto?,
    @field:Schema(description = "참석자 사용자 ID")
    val attendeeUserIds: List<Long>,
    @field:Schema(description = "시작 몇 분 전에 알릴지", example = "[10]")
    val reminderMinutes: List<Int>,
) {
    companion object {
        fun of(item: CalendarItem): TeamCalendarOccurrenceResDto {
            val entry = item.entry
            val occurrence = item.occurrence
            return TeamCalendarOccurrenceResDto(
                source = entry.source,
                eventId = entry.eventId,
                projectId = entry.projectId,
                originalStartAt = occurrence.originalStartAt,
                startAt = occurrence.startAt,
                endAt = occurrence.endAt,
                title = occurrence.title ?: entry.title,
                description = occurrence.resolveDescription(entry.description),
                allDay = entry.allDay,
                timeZone = entry.timeZone,
                recurring = entry.series.recurrence != null,
                createdBy = entry.createdBy,
                category = entry.category?.let(TeamCalendarCategoryResDto::of),
                attendeeUserIds = entry.attendeeUserIds,
                reminderMinutes = entry.reminderMinutes,
            )
        }
    }
}
