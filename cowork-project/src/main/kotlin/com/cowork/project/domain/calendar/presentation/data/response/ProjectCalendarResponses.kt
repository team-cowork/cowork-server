package com.cowork.project.domain.calendar.presentation.data.response

import com.cowork.project.domain.calendar.entity.ProjectCalendarCategory
import com.cowork.project.domain.calendar.entity.ProjectCalendarEvent
import com.cowork.project.domain.calendar.model.CalendarOccurrence
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class ProjectCalendarCategoryResDto(
    @field:Schema(description = "카테고리 ID", example = "1")
    val id: Long,
    @field:Schema(description = "카테고리 이름", example = "회의")
    val name: String,
    @field:Schema(description = "색", example = "#4F46E5")
    val colorHex: String,
    @field:Schema(description = "만든 사용자 ID", example = "7")
    val createdBy: Long,
) {
    companion object {
        fun of(category: ProjectCalendarCategory) =
            ProjectCalendarCategoryResDto(category.id, category.name, category.colorHex, category.createdBy)
    }
}

data class ProjectCalendarRecurrenceResDto(
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
)

data class ProjectCalendarEventResDto(
    @field:Schema(description = "일정 ID", example = "1")
    val id: Long,
    @field:Schema(description = "프로젝트 ID", example = "1")
    val projectId: Long,
    @field:Schema(description = "만든 사용자 ID", example = "7")
    val createdBy: Long,
    @field:Schema(description = "제목", example = "스프린트 회고")
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
    @field:Schema(description = "카테고리", nullable = true)
    val category: ProjectCalendarCategoryResDto?,
    @field:Schema(description = "참석자 사용자 ID")
    val attendeeUserIds: List<Long>,
    @field:Schema(description = "시작 몇 분 전에 알릴지", example = "[10]")
    val reminderMinutes: List<Int>,
    @field:Schema(description = "반복 규칙", nullable = true)
    val recurrence: ProjectCalendarRecurrenceResDto?,
) {
    companion object {
        fun of(event: ProjectCalendarEvent, category: ProjectCalendarCategory?) = ProjectCalendarEventResDto(
            id = event.id,
            projectId = event.projectId,
            createdBy = event.createdBy,
            title = event.title,
            description = event.description,
            startAt = event.startAt,
            endAt = event.endAt,
            allDay = event.allDay,
            timeZone = event.timeZone,
            category = category?.takeIf { it.id == event.categoryId }?.let(ProjectCalendarCategoryResDto::of),
            attendeeUserIds = event.attendeeUserIds.sorted(),
            reminderMinutes = event.reminders,
            recurrence = event.recurrence?.let {
                ProjectCalendarRecurrenceResDto(
                    it.frequency.name,
                    it.interval,
                    it.byDay.sorted().map { day -> day.name },
                    it.until,
                    it.count,
                )
            },
        )
    }
}

data class ProjectCalendarOccurrenceResDto(
    @field:Schema(description = "일정 ID", example = "1")
    val eventId: Long,
    @field:Schema(description = "반복 규칙이 만든 원래 시작 시각. 이 발생분만 수정하거나 삭제할 때 씁니다.")
    val originalStartAt: Instant,
    @field:Schema(description = "시작 시각")
    val startAt: Instant,
    @field:Schema(description = "종료 시각")
    val endAt: Instant,
    @field:Schema(description = "제목", example = "스프린트 회고")
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
    @field:Schema(description = "카테고리", nullable = true)
    val category: ProjectCalendarCategoryResDto?,
    @field:Schema(description = "참석자 사용자 ID")
    val attendeeUserIds: List<Long>,
    @field:Schema(description = "시작 몇 분 전에 알릴지", example = "[10]")
    val reminderMinutes: List<Int>,
) {
    companion object {
        fun of(event: ProjectCalendarEvent, occurrence: CalendarOccurrence, category: ProjectCalendarCategory?) =
            ProjectCalendarOccurrenceResDto(
                eventId = event.id,
                originalStartAt = occurrence.originalStartAt,
                startAt = occurrence.startAt,
                endAt = occurrence.endAt,
                title = occurrence.title ?: event.title,
                description = occurrence.description ?: event.description,
                allDay = event.allDay,
                timeZone = event.timeZone,
                recurring = event.recurrenceFrequency != null,
                createdBy = event.createdBy,
                category = category?.takeIf { it.id == event.categoryId }?.let(ProjectCalendarCategoryResDto::of),
                attendeeUserIds = event.attendeeUserIds.sorted(),
                reminderMinutes = event.reminders,
            )
    }
}
