package com.cowork.project.domain.calendar.presentation.controller

import com.cowork.project.domain.calendar.model.CalendarEditScope
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarCategoryReqDto
import com.cowork.project.domain.calendar.presentation.data.request.SaveProjectCalendarEventReqDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarCategoryResDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarEventResDto
import com.cowork.project.domain.calendar.presentation.data.response.ProjectCalendarOccurrenceResDto
import com.cowork.project.domain.calendar.service.ManageProjectCalendarCategoryService
import com.cowork.project.domain.calendar.service.ManageProjectCalendarEventService
import com.cowork.project.domain.calendar.service.QueryProjectCalendarCategoriesService
import com.cowork.project.domain.calendar.service.QueryProjectCalendarEventsService
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
import team.themoment.sdk.response.CommonApiResponse
import java.time.Instant

@Tag(name = "프로젝트 캘린더", description = "프로젝트 일정과 일정 카테고리 API")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "액세스 토큰이 없거나 유효하지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "403",
        description = "프로젝트 읽기 권한 또는 일정 권한 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "프로젝트, 일정 또는 카테고리 없음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "권한 정보가 아직 동기화되지 않음",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
    ApiResponse(
        responseCode = "default",
        description = "기타 오류",
        content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
    ),
)
@RestController
@RequestMapping("/projects/{projectId}/calendar")
class ProjectCalendarController(
    private val manageProjectCalendarEventService: ManageProjectCalendarEventService,
    private val queryProjectCalendarEventsService: QueryProjectCalendarEventsService,
    private val manageProjectCalendarCategoryService: ManageProjectCalendarCategoryService,
    private val queryProjectCalendarCategoriesService: QueryProjectCalendarCategoriesService,
) {

    @Operation(
        summary = "기간 안의 일정 조회",
        description = "반복 일정을 기간 안의 발생분으로 펼쳐 시작 시각 순으로 돌려줍니다. 기간은 최대 93일입니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "기간이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @GetMapping("/events")
    fun getEvents(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @Parameter(description = "기간 시작. 이 시각 이후에 끝나는 발생분을 넣습니다.", example = "2026-12-01T00:00:00Z")
        @RequestParam from: Instant,
        @Parameter(description = "기간 끝. 이 시각 전에 시작하는 발생분을 넣습니다.", example = "2027-01-01T00:00:00Z")
        @RequestParam to: Instant,
    ): List<ProjectCalendarOccurrenceResDto> = queryProjectCalendarEventsService.query(userId, projectId, from, to)

    @Operation(summary = "일정 원본 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping("/events/{eventId}")
    fun getEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable eventId: Long,
    ): ProjectCalendarEventResDto = queryProjectCalendarEventsService.get(userId, projectId, eventId)

    @Operation(
        summary = "일정 만들기",
        description = "프로젝트 읽기 권한과 일정 만들기 권한이 필요합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "시각, 시간대, 반복 규칙, 리마인더, 카테고리 또는 참석자가 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @Valid @RequestBody request: SaveProjectCalendarEventReqDto,
    ): ProjectCalendarEventResDto = manageProjectCalendarEventService.create(userId, projectId, request)

    @Operation(
        summary = "일정 수정",
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
        @PathVariable projectId: Long,
        @PathVariable eventId: Long,
        @Parameter(description = "반복 일정의 수정 범위") @RequestParam(defaultValue = "ALL") scope: CalendarEditScope,
        @Parameter(description = "THIS와 FOLLOWING에서 고른 발생분의 원래 시작 시각")
        @RequestParam(required = false)
        occurrenceStartAt: Instant?,
        @Valid @RequestBody request: SaveProjectCalendarEventReqDto,
    ): ProjectCalendarEventResDto =
        manageProjectCalendarEventService.update(userId, projectId, eventId, scope, occurrenceStartAt, request)

    @Operation(
        summary = "일정 삭제",
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
        @PathVariable projectId: Long,
        @PathVariable eventId: Long,
        @Parameter(description = "반복 일정의 삭제 범위") @RequestParam(defaultValue = "ALL") scope: CalendarEditScope,
        @Parameter(description = "THIS와 FOLLOWING에서 고른 발생분의 원래 시작 시각")
        @RequestParam(required = false)
        occurrenceStartAt: Instant?,
    ) = manageProjectCalendarEventService.delete(userId, projectId, eventId, scope, occurrenceStartAt)

    @Operation(summary = "일정 카테고리 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @GetMapping("/categories")
    fun getCategories(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): List<ProjectCalendarCategoryResDto> = queryProjectCalendarCategoriesService.execute(userId, projectId)

    @Operation(
        summary = "일정 카테고리 만들기",
        description = "프로젝트 읽기 권한과 일정 만들기 권한이 필요합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "이름 또는 색상 형식이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "같은 이름의 카테고리가 있음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCategory(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @Valid @RequestBody request: SaveProjectCalendarCategoryReqDto,
    ): ProjectCalendarCategoryResDto = manageProjectCalendarCategoryService.create(userId, projectId, request)

    @Operation(
        summary = "일정 카테고리 수정",
        description = "만든 사람은 권한 없이 수정합니다. 다른 사람의 카테고리는 다른 사람의 일정 수정 권한이 필요합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "이름 또는 색상 형식이 올바르지 않음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "같은 이름의 카테고리가 있음",
            content = [Content(schema = Schema(implementation = CommonApiResponse::class))],
        ),
    )
    @PutMapping("/categories/{categoryId}")
    fun updateCategory(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable categoryId: Long,
        @Valid @RequestBody request: SaveProjectCalendarCategoryReqDto,
    ): ProjectCalendarCategoryResDto =
        manageProjectCalendarCategoryService.update(userId, projectId, categoryId, request)

    @Operation(
        summary = "일정 카테고리 삭제",
        description = "카테고리를 쓰던 일정은 카테고리 없이 남습니다. " +
            "만든 사람은 권한 없이 삭제합니다. 다른 사람의 카테고리는 다른 사람의 일정 삭제 권한이 필요합니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(ApiResponse(responseCode = "204", description = "삭제 성공", content = [Content()]))
    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCategory(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable categoryId: Long,
    ) = manageProjectCalendarCategoryService.delete(userId, projectId, categoryId)
}
