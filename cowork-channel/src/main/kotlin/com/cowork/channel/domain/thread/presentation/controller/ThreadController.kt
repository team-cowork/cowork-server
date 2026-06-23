package com.cowork.channel.domain.thread.presentation.controller

import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.request.UpdateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.service.ThreadService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "스레드", description = "채널 스레드 생성/수정/조회 API")
@RestController
@RequestMapping("/channels/{channelId}/threads")
class ThreadController(private val threadService: ThreadService) {

    @Operation(summary = "스레드 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "채널 멤버가 아님"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createThread(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateThreadRequest,
    ): ThreadResponse = threadService.createThread(userId, channelId, request)

    @Operation(summary = "스레드 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @GetMapping
    fun getThreads(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<ThreadResponse> = threadService.getThreads(userId, channelId, includeArchived, pageable)

    @Operation(summary = "스레드 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PatchMapping("/{threadId}")
    fun updateThread(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable threadId: Long,
        @RequestBody request: UpdateThreadRequest,
    ): ThreadResponse = threadService.updateThread(userId, channelId, threadId, request)
}
