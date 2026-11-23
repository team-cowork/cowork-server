package com.cowork.team.domain.calendar.presentation.controller

import com.cowork.team.domain.calendar.model.CalendarEditScope
import com.cowork.team.domain.calendar.model.TeamCalendarFilter
import com.cowork.team.domain.calendar.presentation.data.request.SaveTeamCalendarEventReqDto
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarEventResDto
import com.cowork.team.domain.calendar.presentation.data.response.TeamCalendarOccurrenceResDto
import com.cowork.team.domain.calendar.service.ManageTeamCalendarEventService
import com.cowork.team.domain.calendar.service.QueryTeamCalendarService
import com.cowork.team.global.openapi.TeamErrorResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.response.CommonApiResponse
import java.time.Instant

@Tag(name = "팀 캘린더", description = "팀 일정과 프로젝트 일정 통합 조회 API")
@TeamErrorResponses
@ApiResponses(
    ApiResponse(
        responseCode = "403",
        description = "팀 멤버가 아니거나 일정 권한 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "팀 또는 일정 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "권한 정보나 프로젝트 일정이 아직 동기화되지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/teams/{teamId}/calendar")
class TeamCalendarController(
    private val manageTeamCalendarEventService: ManageTeamCalendarEventService,
    private val queryTeamCalendarService: QueryTeamCalendarService,
) {

    @Operation(
        summary = "팀 캘린더 조회",
        description = "팀 일정과 요청자가 읽을 수 있는 프로젝트의 일정을 기간 안의 발생분으로 펼쳐 시작 시각 순으로 돌려줍니다. " +
            "기간은 최대 93일입니다. 필터를 여러 개 주면 모두 만족하는 일정만, 한 필터에 값을 여러 개 주면 하나라도 맞는 일정을 돌려줍니다. " +
            "카테고리 필터를 주면 카테고리가 없는 팀 일정은 빠집니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "기간 또는 필터 값이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping("/events")
    fun getEvents(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @Parameter(description = "기간 시작. 이 시각 이후에 끝나는 발생분을 넣습니다.", example = "2026-12-01T00:00:00Z")
        @RequestParam from: Instant,
        @Parameter(description = "기간 끝. 이 시각 전에 시작하는 발생분을 넣습니다.", example = "2027-01-01T00:00:00Z")
        @RequestParam to: Instant,
        @Parameter(description = "프로젝트 ID 목록. teamOnly는 프로젝트에 속하지 않는 팀 일정을 뜻합니다.", example = "[\"teamOnly\", \"3\"]")
        @RequestParam(required = false)
        projectIds: List<String>?,
        @Parameter(description = "프로젝트 일정 카테고리 ID 목록")
        @RequestParam(required = false)
        categoryIds: List<Long>?,
        @Parameter(description = "참석자 사용자 ID 목록")
        @RequestParam(required = false)
        attendeeUserIds: List<Long>?,
    ): List<TeamCalendarOccurrenceResDto> {
        val filter = TeamCalendarFilter.of(projectIds, categoryIds, attendeeUserIds)
            ?: throw ExpectedException("프로젝트 필터는 양수 ID 또는 teamOnly여야 합니다.", HttpStatus.BAD_REQUEST)
        return queryTeamCalendarService.query(userId, teamId, from, to, filter)
    }

    @Operation(summary = "팀 일정 원본 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping("/events/{eventId}")
    fun getEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable eventId: Long,
    ): TeamCalendarEventResDto = queryTeamCalendarService.get(userId, teamId, eventId)

    @Operation(
        summary = "팀 일정 만들기",
        description = "팀 멤버이고 일정 만들기 권한이 있어야 합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "시각, 시간대, 반복 규칙, 리마인더 또는 참석자가 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @Valid @RequestBody request: SaveTeamCalendarEventReqDto,
    ): TeamCalendarEventResDto = manageTeamCalendarEventService.create(userId, teamId, request)

    @Operation(
        summary = "팀 일정 수정",
        description = "작성자는 권한 없이 수정합니다. 다른 사람의 일정은 다른 사람의 일정 수정 권한이 필요합니다. " +
            "THIS는 제목, 설명, 시작 시각, 종료 시각만 바꿉니다. FOLLOWING은 원본을 그 발생분 직전에 끝내고 " +
            "그 발생분부터 새 일정을 만들어 돌려줍니다. 반복하지 않는 일정은 범위와 관계없이 전체를 바꿉니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "요청 값 또는 발생분 시작 시각이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PutMapping("/events/{eventId}")
    fun updateEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable eventId: Long,
        @Parameter(description = "반복 일정의 수정 범위") @RequestParam(defaultValue = "ALL") scope: CalendarEditScope,
        @Parameter(description = "THIS와 FOLLOWING에서 고른 발생분의 원래 시작 시각")
        @RequestParam(required = false)
        occurrenceStartAt: Instant?,
        @Valid @RequestBody request: SaveTeamCalendarEventReqDto,
    ): TeamCalendarEventResDto =
        manageTeamCalendarEventService.update(userId, teamId, eventId, scope, occurrenceStartAt, request)

    @Operation(
        summary = "팀 일정 삭제",
        description = "작성자는 권한 없이 삭제합니다. 다른 사람의 일정은 다른 사람의 일정 삭제 권한이 필요합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]),
        ApiResponse(
            responseCode = "400",
            description = "발생분 시작 시각이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @DeleteMapping("/events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable eventId: Long,
        @Parameter(description = "반복 일정의 삭제 범위") @RequestParam(defaultValue = "ALL") scope: CalendarEditScope,
        @Parameter(description = "THIS와 FOLLOWING에서 고른 발생분의 원래 시작 시각")
        @RequestParam(required = false)
        occurrenceStartAt: Instant?,
    ) = manageTeamCalendarEventService.delete(userId, teamId, eventId, scope, occurrenceStartAt)
}
