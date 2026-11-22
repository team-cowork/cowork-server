package com.cowork.project.domain.calendar.presentation.data.request

import com.cowork.project.domain.calendar.model.RecurrenceFrequency
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant

data class SaveProjectCalendarEventReqDto(
    @get:Schema(description = "제목", example = "스프린트 회고", maxLength = 200, requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,

    @get:Schema(description = "설명", example = "이번 스프린트를 돌아봅니다.", maxLength = 2000)
    @field:Size(max = 2000)
    val description: String? = null,

    @get:Schema(
        description = "시작 시각. 반복 일정은 첫 발생분의 시작 시각입니다.",
        example = "2026-12-01T01:00:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val startAt: Instant?,

    @get:Schema(
        description = "종료 시각. 시작 시각보다 앞설 수 없습니다.",
        example = "2026-12-01T02:00:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val endAt: Instant?,

    @get:Schema(description = "종일 일정 여부", example = "false", defaultValue = "false")
    val allDay: Boolean = false,

    @get:Schema(description = "IANA 시간대. 반복은 이 시간대의 현지 시각으로 계산합니다.", example = "Asia/Seoul")
    @field:Size(max = 64)
    val timeZone: String = "Asia/Seoul",

    @get:Schema(description = "일정 카테고리 ID", example = "1")
    val categoryId: Long? = null,

    @get:Schema(description = "참석자 사용자 ID. 프로젝트를 볼 수 있는 팀 멤버만 고를 수 있습니다.", maxLength = 100)
    @field:Size(max = 100)
    val attendeeUserIds: List<Long> = emptyList(),

    @get:Schema(
        description = "시작 몇 분 전에 알릴지 정합니다. 0, 10, 30, 60, 1440 중 최대 2개를 고릅니다.",
        example = "[10, 1440]",
    )
    @field:Size(max = 2)
    val reminderMinutes: List<Int> = emptyList(),

    @get:Schema(description = "반복 규칙. 없으면 반복하지 않습니다.")
    @field:Valid
    val recurrence: ProjectCalendarRecurrenceReqDto? = null,
)

data class ProjectCalendarRecurrenceReqDto(
    @get:Schema(description = "반복 주기", example = "WEEKLY", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val frequency: RecurrenceFrequency?,

    @get:Schema(description = "반복 간격", example = "1", minimum = "1", maximum = "99", defaultValue = "1")
    @field:Min(1)
    @field:Max(99)
    val interval: Int = 1,

    @get:Schema(description = "WEEKLY에서 반복할 요일. 없으면 시작 요일에 반복합니다.", example = "[\"MONDAY\", \"WEDNESDAY\"]")
    val byDay: Set<DayOfWeek> = emptySet(),

    @get:Schema(description = "마지막 발생분이 시작할 수 있는 시각. count와 함께 쓸 수 없습니다.", example = "2027-03-01T00:00:00Z")
    val until: Instant? = null,

    @get:Schema(description = "전체 발생 수. until과 함께 쓸 수 없습니다.", example = "10", minimum = "1", maximum = "1000")
    @field:Min(1)
    @field:Max(1000)
    val count: Int? = null,
)

data class SaveProjectCalendarCategoryReqDto(
    @get:Schema(description = "카테고리 이름. 프로젝트 안에서 겹칠 수 없습니다.", example = "회의", maxLength = 50)
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,

    @get:Schema(description = "색", example = "#4F46E5", pattern = "^#[0-9A-Fa-f]{6}$")
    @field:Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    val colorHex: String,
)
