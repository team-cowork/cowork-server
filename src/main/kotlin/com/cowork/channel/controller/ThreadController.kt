package com.cowork.channel.controller

import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.dto.UpdateThreadRequest
import com.cowork.channel.service.ThreadService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "스레드")
@RestController
@RequestMapping("/channels/{channelId}/threads")
class ThreadController(
    private val threadService: ThreadService,
) {

    @Operation(summary = "스레드 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: CreateThreadRequest,
    ): ThreadResponse = threadService.create(channelId, user.id, request)

    @Operation(summary = "스레드 목록 조회")
    @GetMapping
    fun getThreads(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "false") archived: Boolean,
    ): List<ThreadResponse> = threadService.getThreads(channelId, user.id, archived)

    @Operation(summary = "스레드 수정 및 보관")
    @PatchMapping("/{threadId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable threadId: Long,
        @Valid @RequestBody request: UpdateThreadRequest,
    ): ThreadResponse = threadService.update(channelId, threadId, user.id, request)
}
